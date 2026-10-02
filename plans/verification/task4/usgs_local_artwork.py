import json,pathlib,math,importlib.util,numpy as np,pymupdf
import argparse,hashlib
parser=argparse.ArgumentParser(description="Pinned USGS local vector artwork versus independent binding registration; no native renderer is consulted.")
parser.add_argument('--pdf',type=pathlib.Path)
parser.add_argument('--zoom',type=float,default=20)
parser.add_argument('--density',type=float,default=2.625)
parser.add_argument('--output',type=pathlib.Path)
args=parser.parse_args()
R=pathlib.Path(__file__).resolve().parents[3]
pdf=args.pdf or R/'samples/USGS_SF_North.pdf'
assert math.isfinite(args.zoom) and math.isfinite(args.density) and args.density>0
spec=importlib.util.spec_from_file_location('geo_generator',R/'scripts/gen_test_geopdfs.py');g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g);ref=g.Ref();G=json.loads((R/'testdata/pdf_georef.json').read_text());source=G['sheets'][-1];ex=source['expected'];aff=ex['affine'];assert hashlib.sha256(pdf.read_bytes()).hexdigest()==g.USGS_SHA256;doc=pymupdf.open(pdf);pg=doc[0];H=pg.mediabox.height;groups={'E':{},'N':{}};segments={'E':{},'N':{}}
for path in pg.get_drawings():
 c=path.get('color');w=path.get('width')or 0
 if not c or abs(c[0]-1)>.01 or abs(c[1]-.667)>.01 or c[2]>.01 or abs(w-.18)>.005:continue
 for it in path['items']:
  if it[0]!='l':continue
  a,b=it[1:];ax,ay,bx,by=a.x,H-a.y,b.x,H-b.y
  if math.hypot(bx-ax,by-ay)<15:continue
  ang=math.degrees(math.atan2(by-ay,bx-ax))%180;E,N=ref.apply(aff,(ax+bx)/2,(ay+by)/2)
  if abs(ang-90)<2:axis='E';coordinate=int(round(E/1000))*1000;delta=abs(E-coordinate)
  elif ang<2 or ang>178:axis='N';coordinate=int(round(N/1000))*1000;delta=abs(N-coordinate)
  else:continue
  if delta<60:
   groups[axis].setdefault(coordinate,set()).update([(ax,ay),(bx,by)]);segments[axis].setdefault(coordinate,[]).append([ax,ay,bx,by])
lines={axis:{str(k):{'fit':np.polyfit([p[1 if axis=='E'else 0]for p in pts],[p[0 if axis=='E'else 1]for p in pts],1).tolist(),'vertices':sorted(pts),'verticesCount':len(pts)}for k,pts in grouped.items()if len(pts)>40}for axis,grouped in groups.items()}
cam={'zoom':args.zoom};density=args.density;E,N=550000,4185000;mv,cv=lines['E'][str(E)]['fit'];mh,ch=lines['N'][str(N)]['fit'];py=(mh*cv+ch)/(1-mh*mv);px=mv*py+cv;X,Y=ref.apply(aff,px,py);wlat,wlon=ref.to_wgs84('NAD83',*ref.inv(ex['crs'],'NAD83',X,Y));tlat,tlon=ref.to_wgs84('NAD83',*ref.inv(ex['crs'],'NAD83',E,N));Earth=6378137;mpu=2*math.pi*Earth/(256*2**cam['zoom']);xdelta=Earth*math.radians(wlon-tlon)/mpu*density;ydelta=-(Earth*math.log(math.tan(math.pi/4+math.radians(wlat)/2))-Earth*math.log(math.tan(math.pi/4+math.radians(tlat)/2)))/mpu*density
local={}
for axis,key in [('E',E),('N',N)]:
 target=py if axis=='E'else px;closest=[]
 for ax,ay,bx,by in segments[axis][key]:
  lo,hi=sorted([ay,by]if axis=='E'else[ax,bx])
  if lo<=target<=hi:
   value=ax+(bx-ax)*(target-ay)/(by-ay)if axis=='E'else ay+(by-ay)*(target-ax)/(bx-ax);closest.append({'segment':[ax,ay,bx,by],'valueAtModelIntersection':value})
 local[axis]=closest
localFit={}
for axis in ['E','N']:
 ax,ay,bx,by=local[axis][0]['segment'];m=(bx-ax)/(by-ay)if axis=='E'else(by-ay)/(bx-ax);c=ax-m*ay if axis=='E'else ay-m*ax;localFit[axis]=(m,c)
mv,cv=localFit['E'];mh,ch=localFit['N'];ly=(mh*cv+ch)/(1-mh*mv);lx=mv*ly+cv;LX,LY=ref.apply(aff,lx,ly);lla,llo=ref.to_wgs84('NAD83',*ref.inv(ex['crs'],'NAD83',LX,LY));localPixelX=Earth*math.radians(llo-tlon)/mpu*density;localPixelY=-(Earth*math.log(math.tan(math.pi/4+math.radians(lla)/2))-Earth*math.log(math.tan(math.pi/4+math.radians(tlat)/2)))/mpu*density
result={'localExactIntersection':{'page':[lx,ly],'modelPlane':[LX,LY],'geodeticMetres':ref.geod_m(tlat,tlon,lla,llo),'predictedPhysicalPixelDisplacementAtActualZ20':[localPixelX,localPixelY]},'localActualSegments':local,'sourceSha256':g.USGS_SHA256,'gridLines':lines,'selectedIntersection':{'gridNAD83':[E,N],'independentArtworkPageFit':[px,py],'modelMapsArtworkToPlane':[X,Y],'independentGeodeticDisplacementMetres':ref.geod_m(tlat,tlon,wlat,wlon),'predictedIntrinsicPhysicalPixelDisplacementAtActualZ20':[xdelta,ydelta]},'meaning':'Actual PDF orange paths fitted independently. Source intrinsic registration, before native rasterization. No app georef or generated screenshot.'}
result['physicalScale']={'zoom':args.zoom,'density':args.density}
text=json.dumps(result,indent=2,allow_nan=False)
if args.output:args.output.write_text(text+'\n')
print(json.dumps({'selectedIntersection':result['selectedIntersection'],'localExactIntersection':result['localExactIntersection'],'physicalScale':result['physicalScale']},indent=2,allow_nan=False))
