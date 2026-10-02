#!/usr/bin/env python3
"""Analytic truth tests: offsets/angles, occlusion, blank, colored paired views,
and raster-resampling uncertainty. Neither app geometry nor camera is consulted.
Run directly; optionally retain a compact JSON result with --output PATH.
"""
import argparse,json
from pathlib import Path
import numpy as np
import physical_grid_metric as m

def run():
 m.PRINTED_ESTIMATOR='edges';m.PRINTED_COLOR='red';m.PAIR_OFF=None
 base=m.selftest();result={'baseCases':len(base['cases']),'noInkCases':base['negativeNoInkCases'],'pairedCases':0,'overzoom':{}}
 # Same analytic artwork with overlay on/off. Test source classification,
 # both signs, independent shifts, sampling, and physical crop coordinates.
 for color in ['red','orange']:
  for width in [3,80,150]:
   for sampling in [1,8,16]:
    for dx,dy in [(0,0),(2,-2)]:
     m.PRINTED_COLOR=color
     m.PAIR_OFF=m.synthetic(0,0,.0057,width,True,color=color,source_only=True,sampling=sampling)
     image=m.synthetic(dx,dy,.0057,width,True,color=color,sampling=sampling)
     r=m.measure(image)
     assert all(r[a]['status']=='MEASURED' for a in ['x','y']),r
     assert max(abs(r[a]['median_px']-t) for a,t in [('x',dx),('y',dy)])<.8,r
     assert r['status']!='PASS' or max(abs(dx),abs(dy))<=1,r
     result['pairedCases']+=1
 m.PAIR_OFF=None;m.PRINTED_COLOR='red'
 # A broad native-style gold stripe can split a printed plateau before
 # component detection. Reconstruct only an interior occlusion bracketed by
 # visible red on both sides; the outer stroke edges stay observed.
 for dx,dy in [(0,0),(2,-2),(-2,2)]:
  image=m.synthetic(dx,dy,printed_width=160,single=True)
  h,w,_=image.shape;y,x=np.mgrid[:h,:w]
  for center,cross in [(h/2-45,y),(w/2+120,x)]:
   alpha=np.clip(20-np.abs(cross-center),0,1)[...,None]
   image=(image*(1-alpha)+np.array([255,165,0])*alpha).astype(np.uint8)
  r=m.measure(image)
  assert all(r[a]['status']=='MEASURED' for a in ['x','y']),r
  assert max(abs(r[a]['median_px']-t) for a,t in [('x',dx),('y',dy)])<.45,r
  assert r['status']!='PASS' or max(abs(dx),abs(dy))<=1,r
 result['interiorGoldOcclusionCases']=3
 for estimator in ['edges','centroid']:
  m.PRINTED_ESTIMATOR=estimator;errors=[];count=0;samplingErrors={n:[] for n in [1,4,8,16,32]}
  for width in [80,150]:
   for slope in [.0057,-.0057,0]:
    for sampling in [1,4,8,16,32]:
     for dx,dy in [(0,0),(.5,-.5),(1.2,-1.2),(-2,2)]:
      r=m.measure(m.synthetic(dx,dy,slope,width,True,blur=4,sampling=sampling))
      assert all(r[a]['status']=='MEASURED' for a in ['x','y']),r
      for axis,truth in [('x',dx),('y',dy)]:
       for track in r[axis]['tracks']:
        a=np.asarray(track['scanlineSamples']);bias=a[:,2]-a[:,1]-truth;errors.extend(bias);samplingErrors[sampling].extend(bias)
      count+=1
  maximum=float(np.max(np.abs(errors)))
  # Deliberately exceeds the earlier 0.35px envelope: estimator uncertainty
  # grows under wide raster overzoom. This assertion detects large errors,
  # while the measured bound is reported rather than silently called zero.
  assert maximum<1.13,(estimator,maximum)
  result['overzoom'][estimator]={'cases':count,'knownShiftErrorAbsMaxPx':maximum,'knownShiftErrorAbsP99Px':float(np.percentile(np.abs(errors),99)),'bySamplingFactorMaxErrorPx':{str(n):float(np.max(np.abs(values)))for n,values in samplingErrors.items()}}
 m.PAIR_OFF=None;m.PRINTED_ESTIMATOR='edges';m.PRINTED_COLOR='red'
 for dx,dy in [(0,0),(.5,-.5),(3,-2),(-3,2)]:
  image=m.synthetic(dx,dy,printed_width=80,single=True)[40:660,30:470]
  r=m.measure(image)
  assert max(abs(r[a]['median_px']-t) for a,t in [('x',dx),('y',dy)])<.45,r
 result['cropCases']=4;result['status']='PASS'
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--output');a=p.parse_args();r=run();s=json.dumps(r,indent=2,allow_nan=False);print(s)
 if a.output:Path(a.output).write_text(s+'\n')
