#!/usr/bin/env python3
"""Independent raw-pixel ridge metric. No app georef or zoom is used.
Printed red ridge midpoint uses its two half-contrast edges, unaffected by
an interior dark overpaint. Overlay uses neutral/dark-red ink darkness centroid.
Pairs within 12 pixels are sampled along rows/columns and fitted jointly in
original screenshot coordinates. One line is sufficient; extra unmatched overlay
lines do not imply printed spacing. No-ink/missing axis is UNMEASURABLE, never PASS.
UI and gold crosshair occlusion are rejected through chroma/intensity/support.
Single-line angles/translation are retained; neither mask is rotated independently.
Physical image pixels only; no inferred metres from a lone line.
"""
import argparse,json,math,pathlib
import numpy as np
from PIL import Image, ImageFilter

PRINTED_ESTIMATOR="edges"
PRINTED_COLOR="red"
PAIR_OFF=None
UNCERTAINTY_FLOOR=.35

def components(mask):
 x=np.flatnonzero(mask)
 if not len(x):return []
 splits=np.flatnonzero(np.diff(x)>1)+1
 return [(int(v[0]),int(v[-1])) for v in np.split(x,splits)]
def bridge(mask,gap=6):
 out=mask.copy(); cs=components(mask)
 for (_,b),(c,_) in zip(cs,cs[1:]):
  if c-b-1<=gap:out[b+1:c]=True
 return out
def edge_midpoint(c,a,b):
 # Half-contrast crossings relative to this ridge plateau. Fill dark overpaint
 # only for interior peak detection, never alter its outer boundary samples.
 if PRINTED_ESTIMATOR=="centroid":
  a=max(0,a-20);b=min(len(c)-1,b+20);w=np.clip(c[a:b+1],0,1);return float(np.dot(np.arange(a,b+1),w)/w.sum()) if w.sum()>0 else None
 peak=float(np.percentile(c[a:b+1],85));level=peak/2
 good=np.flatnonzero(c[a:b+1]>=level)
 if not len(good):return None
 l=a+good[0];r=a+good[-1]
 if l==0 or r==len(c)-1:return None
 left=l-1+(level-c[l-1])/(c[l]-c[l-1]) if c[l]!=c[l-1] else float(l)
 right=r+(c[r]-level)/(c[r]-c[r+1]) if c[r]!=c[r+1] else float(r)
 return float((left+right)/2)
def measure_axis(img,axis,stride=4,max_pair=12):
 on=img if axis=='x' else img.transpose(1,0,2);arr=on if PAIR_OFF is None else (PAIR_OFF if axis=='x' else PAIR_OFF.transpose(1,0,2));H,W,_=arr.shape;samples=[]
 for row in range(2,H-2,stride):
  rgb=arr[row].astype(float);r,g,b=rgb.T
  ratio=(g-b)/np.maximum(r-b,1)
  colored=(np.abs(g-b)<12) if PRINTED_COLOR=="red" else ((ratio>.60)&(ratio<.73))
  chroma=r-np.maximum(g,b) if PRINTED_COLOR=="red" else r-b
  printed_contrast=np.clip(chroma*207/(255*np.maximum(r-48,1)),0,1)
  # Only repair an interior gold occlusion with a visibly red plateau on
  # BOTH sides. Never invent a missing outer edge or interpolate a whole line.
  reconstructed=np.zeros(W,dtype=bool)
  if PRINTED_COLOR=="red":
   gold_gap=(ratio>.04)&(ratio<.85)&(r>190)&(g>12)&(r-g>20)
   for a,z in components(gold_gap):
    if a>0 and z+1<W and colored[a-1] and colored[z+1] and min(printed_contrast[a-1],printed_contrast[z+1])>.5:
     printed_contrast[a:z+1]=np.linspace(printed_contrast[a-1],printed_contrast[z+1],z-a+3)[1:-1]
     reconstructed[a:z+1]=True
  printed=bridge((printed_contrast>.08)&((chroma>10)&(r>50)&colored|reconstructed),16)
  # Restrict gold/red-crosshair and black labels. Neutral gray includes overlay
  # on paper; dark red includes overlay on printed red. Long-line support later
  # rejects UI/text candidates, not an expected camera point.
  grey=(np.max(rgb,axis=1)-np.min(rgb,axis=1)<20)&(r>25)&(r<240)
  darkred=(chroma>18)&(r<235)&(b<160)&colored
  overlay=grey|darkred if PAIR_OFF is None else ((arr[row,:,0].astype(float)-on[row,:,0].astype(float)>8)&(arr[row,:,0]>240))
  pp=[]
  for a,z in components(printed):
   if z-a+1>W*.80:continue  # perpendicular printed crossing, not this family
   clean=printed_contrast.copy();valid=colored|(np.max(rgb,axis=1)-np.min(rgb,axis=1)<12)
   if valid.any():clean[~valid]=np.interp(np.flatnonzero(~valid),np.flatnonzero(valid),clean[valid])
   center=edge_midpoint(clean,a,z)
   if center is not None:pp.append(center)
  oo=[]
  for a,z in components(overlay):
   if z-a+1>20:continue
   weights=np.maximum(0,255-r[a:z+1]) if PAIR_OFF is None else np.maximum(0,arr[row,a:z+1,0].astype(float)-on[row,a:z+1,0].astype(float));s=weights.sum()
   if s>0:oo.append(float(np.dot(np.arange(a,z+1),weights)/s))
  gold=(g-b>12)&(r>190)&(g>30)&(r-g>20)&((ratio>.28)&(ratio<.57))
  for p in pp:
   lo=max(0,int(p-max_pair));hi=min(W,int(p+max_pair)+1)
   close=[] if gold[lo:hi].any() else [o for o in oo if abs(o-p)<=max_pair]
   o=min(close,key=lambda o:abs(o-p)) if close else np.nan
   samples.append((row,p,o))
 if not samples:return {'status':'UNMEASURABLE','reason':'no printed ridges','samples':0}
 all_s=np.asarray(samples);ordered=all_s[np.argsort(all_s[:,1])]
 splits=np.flatnonzero(np.diff(ordered[:,1])>20)+1;groups=np.split(ordered,splits);tracks=[]
 for group in groups:
  support=np.unique((group[:,0]*8/H).astype(int));coverage=float(np.ptp(group[:,0])/H)
  if len(group)<20 or len(support)<6 or coverage<.65:continue
  if np.ptp(group[:,1])>W*.25:
   return {'status':'UNMEASURABLE','reason':'printed track families cannot be separated','samples':len(group)}
  s=group[np.isfinite(group[:,2])];matched=float(len(s)/len(group))
  if len(s)<20 or matched<.70 or len(np.unique((s[:,0]*8/H).astype(int)))<6:
   return {'status':'UNMEASURABLE','reason':'long printed line obscured or unmatched','printedSamples':len(group),'matchedFraction':matched}
  t=(s[:,0]-H/2)/H;coef=np.polyfit(t,s[:,2]-s[:,1],2);fit=np.polyval(coef,t);res=s[:,2]-s[:,1]-fit
  residualMAD=float(np.median(abs(res-np.median(res))));valid=abs(res)<=max(1.25,4*residualMAD)
  rejected=float(1-valid.mean())
  if rejected>.10:return {'status':'UNMEASURABLE','reason':'unstable offsets exceed outlier allowance','rejectedFraction':rejected}
  s=s[valid];t=(s[:,0]-H/2)/H;coef=np.polyfit(t,s[:,2]-s[:,1],2);estimated=np.polyval(coef,np.linspace(float(t.min()),float(t.max()),128))
  bands=[]
  for k in range(8):
   ds=(s[:,2]-s[:,1])[(s[:,0]>=k*H/8)&(s[:,0]<(k+1)*H/8)]
   bands.append(float(np.median(ds)) if len(ds) else None)
  maximum=float(max(abs(estimated)));rawmax=float(max(abs(s[:,2]-s[:,1])));q90=float(np.percentile(abs(s[:,2]-s[:,1]),90));uncertainty=max(UNCERTAINTY_FLOOR,residualMAD)
  worst=max(maximum,rawmax)
  tracks.append({'status':'MEASURED','samples':len(s),'bands':bands,'coverage':coverage,'matchedFraction':matched,'rejectedFraction':rejected,'printedPositionMedian':float(np.median(group[:,1])),'scanlineSamples':s.tolist(),'median_px':float(np.median(s[:,2]-s[:,1])),'curve_max_px':maximum,'sample_abs_max_px':rawmax,'sample_abs_p90_px':q90,'residual_mad_px':residualMAD,'raster_uncertainty_px':uncertainty,'max_px':worst,'raw_target_status':'PASS' if worst<=1 else 'FAIL','acceptance':'PASS' if worst+uncertainty<=1 else ('FAIL' if worst-uncertainty>1 else 'BORDERLINE')})
 if not tracks:return {'status':'UNMEASURABLE','reason':'no long printed lines with spatial support','samples':len(samples)}
 maximum=max(t['max_px']for t in tracks);status='PASS' if all(t['acceptance']=='PASS'for t in tracks)else 'FAIL'if any(t['acceptance']=='FAIL'for t in tracks)else 'BORDERLINE'
 return {'status':'MEASURED','tracks':tracks,'max_px':maximum,'median_px':float(np.median([t['median_px']for t in tracks])),'acceptance':status}

def measure(img):
 out={axis:measure_axis(img,axis) for axis in ['x','y']};both=all(v['status']=='MEASURED'for v in out.values());out['status']='UNMEASURABLE' if not both else ('PASS' if all(out[x]['acceptance']=='PASS'for x in ['x','y']) else 'FAIL' if any(out[x]['acceptance']=='FAIL'for x in ['x','y']) else 'BORDERLINE');return out

def synthetic(dx,dy,slope=0,printed_width=3,single=False,crop=False,overlay_slope=None,blur=0,sampling=1,color="red",source_only=False):
 W,H=500,700;y,x=np.mgrid[:H,:W];im=np.ones((H,W,3))*255
 xp=[W/2]if single else [75,175,275,375];yp=[H/2]if single else [80,200,320,440,560]
 for axis,places,offset in [('x',xp,dx),('y',yp,dy)]:
  cross=x if axis=='x'else y;along=y if axis=='x'else x
  for p in places:
   distance=abs(cross-p-slope*(along-(H/2 if axis=='x'else W/2)));a=np.clip(printed_width/2+.5-distance,0,1)[...,None];im=im*(1-a)+np.array([255,0 if color=="red" else 170,0])*a
 if sampling>1:
  im=np.asarray(Image.fromarray(im.astype(np.uint8)).resize((int(W/sampling),int(H/sampling)),Image.Resampling.BICUBIC).resize((W,H),Image.Resampling.BILINEAR)).astype(float)
 if blur:im=np.asarray(Image.fromarray(im.astype(np.uint8)).filter(ImageFilter.GaussianBlur(blur))).astype(float)
 if source_only:return im.astype(np.uint8)
 for axis,places,offset in [('x',xp,dx),('y',yp,dy)]:
  cross=x if axis=='x'else y;along=y if axis=='x'else x
  for p in places:
   distance=abs(cross-p-offset-(slope if overlay_slope is None else overlay_slope)*(along-(H/2 if axis=='x'else W/2)));a=.85*np.clip(1.5+.5-distance,0,1)[...,None];im=im*(1-a)+np.array([48,48,48])*a
 if crop:im[140:170,:]=245;im[:,20:50]=245
 return im.astype(np.uint8)
def selftest():
 rows=[]
 for single,width in [(False,3),(True,80)]:
  for dx,dy,slope in [(0,0,0),(.5,-.5,0),(1,-1,0),(3,-2,0),(-3,2,0),(2,-2,.012),(-2,2,-.012)]:
   a=synthetic(dx,dy,slope,width,single,True);r=measure(a)
   for axis,truth in [('x',dx),('y',dy)]:
    assert r[axis]['status']=='MEASURED',(dx,dy,single,r)
    assert abs(r[axis]['median_px']-truth)<.45,(dx,dy,single,r)
   assert r['status']!='PASS' or max(abs(dx),abs(dy))<=1,(dx,dy,single,r)
   rows.append({'dx':dx,'dy':dy,'slope':slope,'single':single,'printedWidth':width,'result':r})
 for single,width in [(False,3),(True,80)]:
  for tilt in [.006,-.006]:
   r=measure(synthetic(0,0,0,width,single,False,tilt))
   assert r['status']!='PASS',('rotation mismatch masked',r)
   assert r['x']['max_px']>1.6 and r['y']['max_px']>1.1,('rotation not measured',r)
   rows.append({'angleMismatchSlope':tilt,'single':single,'result':r})
 for color in [(255,255,255),(255,0,0),(48,48,48)]:assert measure(np.full((300,400,3),color,dtype=np.uint8))['status']=='UNMEASURABLE'
 return {'status':'PASS','cases':rows,'negativeNoInkCases':3}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('png',nargs='?');p.add_argument('--crop');p.add_argument('--selftest',action='store_true');p.add_argument('--output');p.add_argument('--estimator',choices=['edges','centroid'],default='edges');p.add_argument('--printed-color',choices=['red','orange'],default='red');p.add_argument('--off');p.add_argument('--uncertainty-floor',type=float,default=.35,help='Physical-pixel estimator bound validated for the view; use at least 1.13 for the tested 32x resampling envelope. Does not alter raw_target_status.');a=p.parse_args();PRINTED_ESTIMATOR=a.estimator;PRINTED_COLOR=a.printed_color;UNCERTAINTY_FLOOR=a.uncertainty_floor
 assert math.isfinite(UNCERTAINTY_FLOOR) and UNCERTAINTY_FLOOR>=.35
 if a.selftest:r=selftest()
 else:
  image=np.asarray(Image.open(a.png).convert('RGB'))
  if a.crop:x0,y0,x1,y1=map(int,a.crop.split(','));image=image[y0:y1,x0:x1]
  if a.off:
   PAIR_OFF=np.asarray(Image.open(a.off).convert('RGB'))
   if a.crop:PAIR_OFF=PAIR_OFF[y0:y1,x0:x1]
   assert PAIR_OFF.shape==image.shape
  r=measure(image);r['source']=str(pathlib.Path(a.png).resolve());r['crop']=a.crop;r['estimator']=a.estimator;r['printedColor']=a.printed_color;r['uncertaintyFloorPx']=UNCERTAINTY_FLOOR;r['rawTargetStatus']='UNMEASURABLE' if r['status']=='UNMEASURABLE' else ('PASS' if max(r[x]['max_px'] for x in ['x','y'])<=1 else 'FAIL')
 text=json.dumps(r,indent=2,allow_nan=False);print(text)
 if a.output:pathlib.Path(a.output).write_text(text+'\n')
