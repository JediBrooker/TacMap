"""Analytic pixel-area tests of source-only band estimator, independent of app output."""
import json,math,pathlib
import numpy as np
from native_raster_reference import source_band_center

def run():
    maximum={"edges":0.,"centroid":0.};cases=0
    # Each pixel stores independently integrated coverage of an ideal rectangle.
    # Known offsets/angles alter source coordinates rather than expected output.
    for estimator in maximum:
        for width in (16.,24.,32.):
            for offset in (-3.25,-.8,0,.375,2.6):
                for slope in (-.04,0,.035):
                    for along in (10.5,72.5,145.5):
                        target=120.5+offset+slope*(along-72.5)
                        left,right=target-width/2,target+width/2
                        index=np.arange(256.)
                        contrast=np.maximum(0,np.minimum(index+1,right)-np.maximum(index,left))
                        measured,reason=source_band_center(contrast,target,estimator)
                        assert reason is None and measured is not None
                        error=abs(measured-target);maximum[estimator]=max(maximum[estimator],error)
                        assert error<(.09 if estimator=="edges" else .035),(estimator,width,offset,slope,along,error)
                        # Moving independent association coordinate within the band
                        # must never move the measured printed center.
                        again,_=source_band_center(contrast,target+2,estimator)
                        assert again==measured
                        cases+=1
    for signal,reason in [(np.zeros(256),'missingOrAmbiguousPrintedBand'),(np.ones(256),'perpendicularBand')]:
        measured,actual=source_band_center(signal,128)
        assert measured is None and actual==reason
    # Cropped source: the outer edge must fail rather than invent a center.
    edge=np.zeros(256);edge[:12]=1
    assert source_band_center(edge,6,'edges')[0] is None
    assert source_band_center(edge,6,'centroid')[0] is None
    # Two disconnected broad bands: wrong association yields no measurement.
    edge=np.zeros(256);edge[80:100]=1;edge[140:160]=1
    assert source_band_center(edge,125)[0] is None
    return {'analyticCoverageCases':cases,'maxCanonicalErrorPx':maximum,'physicalScale32MaxErrorPx':{k:v*32 for k,v in maximum.items()},'negativeCases':5,'associationDoesNotMoveMeasuredCenter':True,'status':'PASS','scope':'Integrated rectangular stroke, known offsets and slopes; estimator validation only. Native antialiasing and overzoom uncertainty remains separate.'}
if __name__=='__main__':
    r=run();print(json.dumps(r,indent=2));pathlib.Path(__file__).with_name('native_raster_selftest_summary.json').write_text(json.dumps(r,indent=2)+'\n')
