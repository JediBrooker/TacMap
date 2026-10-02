"""Independent source-only ridge reference for real native render diagnostics.
Reads actual exported native bitmaps, not generated expected screenshots.
The source construction affine and PROJ determine ideal printed-grid positions.
The same native cells allow separate ideal geometry and sampled-ink attribution.
"""
import argparse,importlib.util,json,math,pathlib,collections
import numpy as np
from PIL import Image
import physical_grid_metric as metric
HERE=pathlib.Path(__file__).resolve().parent
REPO=HERE.parents[2]

def source_band_center(contrast, expected, estimator="centroid"):
    """Associate one source ridge; never move it toward the expected position."""
    components = metric.components(contrast > .08)
    if any(b-a+1 > .8*len(contrast) for a,b in components):
        return None, "perpendicularBand"
    choices = [(a,b) for a,b in components if a <= expected <= b+1 and b-a < 100]
    if len(choices) != 1:
        return None, "missingOrAmbiguousPrintedBand"
    a,b = choices[0]
    if a == 0 or b == len(contrast)-1:
        return None, "clippedBand"
    if estimator == "centroid":
        lo=max(0,a-2); hi=min(len(contrast),b+3); weights=contrast[lo:hi]
        center=float(np.dot(np.arange(lo,hi),weights)/weights.sum()) if weights.sum()>0 else None
    else:
        metric.PRINTED_ESTIMATOR="edges"
        center=metric.edge_midpoint(contrast,a,b)
    if center is None:
        return None, "clippedBand"
    return center+.5, None

def analyze(root,manifest,fixture,physical_scale):
    spec=importlib.util.spec_from_file_location('reference_generator',REPO/'scripts/gen_test_geopdfs.py')
    generator=importlib.util.module_from_spec(spec);spec.loader.exec_module(generator);reference=generator.Ref()
    entry=next(e for e in json.loads(manifest.read_text())['entries']if e['id']==fixture)
    truth=entry['constructionTruth'];plane=truth['plane'];datum=truth['datum'];aff=truth['pageToPlane']
    inverse=np.linalg.inv(np.array([[aff[0],aff[1]],[aff[3],aff[4]]]));translation=np.array([aff[2],aff[5]])
    audit=json.loads((root/'native-render-comparison.json').read_text());z,tx,ty,cols,rows=audit['job'];size=audit['tilePx'];assert cols==rows==1
    def grid_pixel(E,N):
        lat,lon=reference.to_wgs84(datum,*reference.inv(plane,datum,E,N));s=math.log(math.tan(math.pi/4+math.radians(lat)/2));return np.array([((lon+180)/360*2**z-tx)*size,((1-s/math.pi)/2*2**z-ty)*size])
    lon=(tx+.5)/2**z*360-180;lat=math.degrees(math.atan(math.sinh(math.pi*(1-2*(ty+.5)/2**z))));dl,do=reference.from_wgs84(datum,lat,lon);E0,N0=reference.fwd(plane,datum,dl,do);fixed={'x':round(E0/truth['gridStep'])*truth['gridStep'],'y':round(N0/truth['gridStep'])*truth['gridStep']}
    targets={}
    for axis in ['x','y']:
        targets[axis]={}
        for row in range(32,size-32):
            along=row+.5;low,high=(N0-10000,N0+10000)if axis=='x'else(E0-10000,E0+10000)
            for _ in range(50):
                mid=(low+high)/2;E,N=(fixed[axis],mid)if axis=='x'else(mid,fixed[axis]);px=grid_pixel(E,N);v=px[1]if axis=='x'else px[0]
                if(v>along if axis=='x'else v<along):low=mid
                else:high=mid
            E,N=(fixed[axis],(low+high)/2)if axis=='x'else((low+high)/2,fixed[axis]);px=grid_pixel(E,N);page=inverse@(np.array([E,N])-translation)
            cells=[c for c in audit['cells']if c['rect'][0]<=px[0]<c['rect'][2]and c['rect'][1]<=px[1]<c['rect'][3]]
            if len(cells)!=1:continue
            a=cells[0]['pageToPx'];native=np.array([a[0]*page[0]+a[1]*page[1]+a[2],a[3]*page[0]+a[4]*page[1]+a[5]])
            targets[axis][row]=(px[0]if axis=='x'else px[1],native[0]if axis=='x'else native[1],page.tolist())
    cross=grid_pixel(fixed['x'],fixed['y'])
    out=[]
    for name in ['vector','staged']:
        image=np.asarray(Image.open(root/(name+'.png')).convert('RGBA'));assert image.shape[:2]==(size,size)
        for estimator in ['edges','centroid']:
            metric.PRINTED_ESTIMATOR=estimator;result={'path':name,'estimator':estimator,'physicalScale':physical_scale,'pixelCenterConvention':'array index i maps to physical bitmap coordinate i+0.5','fixedPlaneGrid':fixed,'crossbarExcludedBitmapPx':48,'centroidPaddingPx':2,'axes':{}}
            for axis in ['x','y']:
                data=image if axis=='x'else image.transpose(1,0,2);samples=[];rejected=collections.Counter()
                for row,(expected,native,page)in targets[axis].items():
                    if abs(row+.5-cross[1 if axis=='x'else 0])<48:
                        rejected['independentlyLocatedCrossbarMargin48px']+=1;continue
                    rgb=data[row,:,:3].astype(float);alpha=data[row,:,3]/255;contrast=np.clip((rgb[:,0]-np.maximum(rgb[:,1],rgb[:,2]))/255*alpha,0,1)
                    center, reason = source_band_center(contrast, expected, estimator)
                    if center is None: rejected[reason]+=1;continue
                    samples.append({'alongBitmapPx':row+.5,'expectedIndependentPx':expected,'idealNativeCellPx':native,'measuredPrintedPx':center,'printedMinusTruthCanonicalPx':center-expected,'printedMinusIdealCellCanonicalPx':center-native,'printedMinusTruthPhysicalPx':(center-expected)*physical_scale,'page':page})
                assert len(samples)>size/2
                result['axes'][axis]={'samples':len(samples),'rejected':dict(rejected),'maxAbsPhysicalPx':max(abs(s['printedMinusTruthPhysicalPx'])for s in samples),'medianPhysicalPx':float(np.median([s['printedMinusTruthPhysicalPx']for s in samples])),'maxAbsSamplingMinusIdealCellPhysicalPx':max(abs(s['printedMinusIdealCellCanonicalPx'])*physical_scale for s in samples),'scanlines':samples}
            out.append(result)
    return {'fixture':fixture,'sourceSHA256':entry['sha256'],'reference':'Original printed source construction coordinates through independent PROJ; actual same-cell native bitmaps. No expected overlay/image generated.','job':audit['job'],'tilePx':size,'cellCount':audit['cellCount'],'timingMs':audit['timingMs'],'stagedWidth':audit['stagedWidth'],'stagedHeight':audit['stagedHeight'],'stagedPixels':audit['stagedPixels'],'stagedPixelCap':audit['stagedPixelCap'],'results':out}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=pathlib.Path,required=True);p.add_argument('--manifest',type=pathlib.Path,default=HERE/'fixture_manifest.json');p.add_argument('--fixture',default='cbr50k_iso');p.add_argument('--physical-scale',type=float,default=32);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();result=analyze(a.root,a.manifest,a.fixture,a.physical_scale);a.output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    for r in result['results']:print(r['path'],r['estimator'],{axis:{k:v for k,v in d.items()if k!='scanlines'}for axis,d in r['axes'].items()})
