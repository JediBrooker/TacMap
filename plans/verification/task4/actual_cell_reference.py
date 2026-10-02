"""Actual observed native job/cell+paint geometry versus independent source PROJ.
No generated image or inferred job geometry. Dense actual viewport and printed
line samples preserve actual physical-pixel residuals; frame/job nonce is fenced.
"""
import pathlib,json,importlib.util,math,numpy as np
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--evidence-root',type=pathlib.Path,default=pathlib.Path(__file__).with_name('observed_android'))
parser.add_argument('--manifest',type=pathlib.Path,default=pathlib.Path(__file__).with_name('fixture_manifest.json'))
parser.add_argument('--output',type=pathlib.Path)
parser.add_argument('--fixtures',nargs='+',default=['sf_iso','edge_iso','syd_iso','usgs_sf_north','real_usgs_sf_north'])
args=parser.parse_args()
R=pathlib.Path(__file__).resolve().parents[3]
s=importlib.util.spec_from_file_location('gen',R/'scripts/gen_test_geopdfs.py');g=importlib.util.module_from_spec(s);s.loader.exec_module(g);ref=g.Ref();M=json.loads(args.manifest.read_text());entries={e['id']:e for e in M['entries']};out=[]
for fixture in args.fixtures:
 e=entries[fixture];D=args.evidence_root/fixture;meta=json.loads((D/'render-audit-z20.json').read_text());audit=json.loads((D/'render-audit-z20-audit.json').read_text());frame=audit['frame'];records=audit['completed']['records'];assert audit['enabled'];assert abs(frame['viewportWidth']*frame['density']-meta['screenshotWidth'])<.001 and abs(frame['viewportHeight']*frame['density']-meta['screenshotHeight'])<.001;assert frame['density']==meta['density'];assert meta['sourceId']==frame['sourceId'];assert meta['contentKey']=='sha256:'+e['sha256'];assert not frame['omittedDraws'] and not audit['frameObservationErrors'];assert not any(audit['completed'][k]for k in ['omittedRecords','failedRecords']);truth=e.get('constructionTruth');crs=truth['plane']if truth else e['expectedReference']['crs'];datum=truth['datum']if truth else e['expectedReference']['datum']['id'];aff=truth['pageToPlane']if truth else e['expectedReference']['affine'];matrix=np.array([[aff[0],aff[1]],[aff[3],aff[4]]]);translation=np.array([aff[2],aff[5]]);inv=np.linalg.inv(matrix);W,H=meta['screenshotWidth'],meta['screenshotHeight'];scale=meta['density'];cam=meta['actualCamera'];Earth=6378137;mpu=2*math.pi*Earth/(256*2**cam['zoom']);mx=Earth*math.radians(cam['longitude']);my=Earth*math.log(math.tan(math.pi/4+math.radians(cam['latitude'])/2));assert frame['zoom']==cam['zoom'] and frame['latitude']==cam['latitude'] and frame['longitude']==cam['longitude'] and frame['heading']==0
 bounds=meta.get('measurementBounds',[200,360,870,2080]);left,top,right,bottom=bounds
 jobs=[]
 for draw in frame['draws']:
  z,tx,ty=draw['source'];matching=[r for r in records if r['renderSourceId']==frame['renderSourceId'] and r['sourceId']==frame['sourceId'] and r['job']['z']==z and r['job']['x']<=tx<r['job']['x']+r['job']['cols'] and r['job']['y']<=ty<r['job']['y']+r['job']['rows']];assert len(matching)==1,(fixture,draw,len(matching));jobs.append((draw,matching[0]));assert draw['unitRect']==[0,0,1,1]
 def world_from_screen(px,py):
  X=mx+(px-W/2)*mpu/scale;Y=my-(py-H/2)*mpu/scale;lo=math.degrees(X/Earth);la=math.degrees(2*math.atan(math.exp(Y/Earth))-math.pi/2);return la,lo,X,Y
 def plane_from_screen(px,py):
  la,lo,_,_=world_from_screen(px,py);dl,do=ref.from_wgs84(datum,la,lo);return ref.fwd(crs,datum,dl,do)
 def true_screen(E,N):
  la,lo=ref.to_wgs84(datum,*ref.inv(crs,datum,E,N));X=Earth*math.radians(lo);Y=Earth*math.log(math.tan(math.pi/4+math.radians(la)/2));return W/2+(X-mx)/mpu*scale,H/2-(Y-my)/mpu*scale
 def residual(px,py):
  E,N=plane_from_screen(px,py);p=inv@(np.array([E,N])-translation);_,_,X,Y=world_from_screen(px,py)
  for draw,rec in jobs:
   l,t,r,b=draw['physicalRect']
   if not(l<=px<=r and t<=py<=b):continue
   job=rec['job'];size=rec['tilePx'];n=2**job['z'];expected=np.array([(X/(2*math.pi*Earth)+.5)*n*size-job['x']*size,(.5-Y/(2*math.pi*Earth))*n*size-job['y']*size]);cells=[c for c in rec['cells']if c['rect'][0]-.001<=expected[0]<=c['rect'][2]+.001 and c['rect'][1]-.001<=expected[1]<=c['rect'][3]+.001];assert len(cells)>=1,(fixture,expected,rec)
   c=cells[0];a=c['pageToPx'];native=np.array([a[0]*p[0]+a[1]*p[1]+a[2],a[3]*p[0]+a[4]*p[1]+a[5]]);tileLocal=native-np.array([(draw['source'][1]-job['x'])*size,(draw['source'][2]-job['y'])*size]);painted=np.array([l+tileLocal[0]/draw['bitmapWidth']*(r-l),t+tileLocal[1]/draw['bitmapHeight']*(b-t)]);delta=painted-[px,py];return {'screen':[px,py],'page':p.tolist(),'residualPhysicalPx':delta.tolist(),'normPhysicalPx':float(np.linalg.norm(delta)),'job':job,'cellRect':c['rect'],'cellErrorCanonicalPx':c['errorPx'],'actualMagnification':[(r-l)/draw['bitmapWidth'],(b-t)/draw['bitmapHeight']]}
  raise AssertionError((fixture,px,py,'no observed painted bitmap'))
 dense=[residual(float(px),float(py))for py in np.linspace(top,bottom,25)for px in np.linspace(left,right,17)];E0,N0=plane_from_screen(W/2,H/2);lines=[]
 for axis in ['E','N']:
  fixed=round((E0 if axis=='E'else N0)/1000)*1000;alongCenter=N0 if axis=='E'else E0
  for target in np.linspace(top if axis=='E'else left,bottom if axis=='E'else right,81):
   low,high=alongCenter-10000,alongCenter+10000
   for _ in range(40):
    mid=(low+high)/2;pos=true_screen(fixed,mid)if axis=='E'else true_screen(mid,fixed);v=pos[1]if axis=='E'else pos[0]
    if(v>target if axis=='E'else v<target):low=mid
    else:high=mid
   px,py=true_screen(fixed,(low+high)/2)if axis=='E'else true_screen((low+high)/2,fixed)
   if left<=px<=right and top<=py<=bottom:
    r=residual(px,py);r.update(printedAxis=axis,printedCoordinate=fixed);lines.append(r)
 def summarize(rows):
  a=np.array([r['residualPhysicalPx']for r in rows]);return {'samples':len(rows),'maxAbsPhysicalPxByAxis':np.max(abs(a),axis=0).tolist(),'maxNormPhysicalPx':max(r['normPhysicalPx']for r in rows),'rangeByAxis':[[float(a[:,i].min()),float(a[:,i].max())]for i in [0,1]]}
 result={'fixture':fixture,'sourceSha256':e['sha256'],'actualCamera':cam,'nonceAndCameraAndHashMatched':True,'geometryReference':'Independent construction affine/PROJ versus actual completed native cell transforms and physically painted bitmap bounds. No raster ink estimator.','denseViewport':summarize(dense),'printedGridLineSamples':summarize(lines),'actuallyPaintedJobs':[{'job':r['job'],'path':r['path'],'tilePx':r['tilePx'],'detailZoom':r['detailZoom'],'cells':len(r['cells'])}for d,r in jobs],'denseSamples':dense,'printedLineSamples':lines};out.append(result);print(json.dumps({k:v for k,v in result.items()if k not in ['denseSamples','printedLineSamples']},indent=2))
if args.output:args.output.write_text(json.dumps(out,indent=2,allow_nan=False)+'\n')
