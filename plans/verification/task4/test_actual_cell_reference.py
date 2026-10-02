#!/usr/bin/env python3
"""Known physical translations and identity/omission negatives against the
compact real observed jobs. Temporary variants never modify captured evidence.
"""
import argparse,copy,json,subprocess,sys,tempfile
from pathlib import Path
HERE=Path(__file__).resolve().parent

def invoke(evidence,output):
 fixtures=sorted(folder.name for folder in evidence.iterdir())
 return subprocess.run([sys.executable,str(HERE/'actual_cell_reference.py'),'--evidence-root',str(evidence),'--fixtures',*fixtures,'--output',str(output)],capture_output=True,text=True)

def run(evidence_root):
 with tempfile.TemporaryDirectory() as temporary:
  root=Path(temporary);evidence=root/'evidence'
  originals={}
  for folder in evidence_root.iterdir():
   originals[folder.name]={name:json.loads((folder/name).read_text())for name in ['render-audit-z20.json','render-audit-z20-audit.json']}
  def install(transform):
   for fixture,files in originals.items():
    folder=evidence/fixture;folder.mkdir(parents=True,exist_ok=True);fresh=copy.deepcopy(files);transform(fresh)
    for name,value in fresh.items():(folder/name).write_text(json.dumps(value,allow_nan=False))
  install(lambda _:None);output=root/'result.json';r=invoke(evidence,output);assert r.returncode==0,r.stderr;baseline=json.loads(output.read_text());translations=[]
  for dx,dy in [(3,-2),(-3,2)]:
   def shift(files):
    audit=files['render-audit-z20-audit.json'];frame=audit['frame']
    for record in audit['completed']['records']:
     draw=next(d for d in frame['draws']if record['job']['z']==d['source'][0]and record['job']['x']<=d['source'][1]<record['job']['x']+record['job']['cols']and record['job']['y']<=d['source'][2]<record['job']['y']+record['job']['rows'])
     l,t,r,b=draw['physicalRect'];sx=(r-l)/draw['bitmapWidth'];sy=(b-t)/draw['bitmapHeight']
     for cell in record['cells']:cell['pageToPx'][2]+=dx/sx;cell['pageToPx'][5]+=dy/sy
   install(shift);r=invoke(evidence,output);assert r.returncode==0,r.stderr;shifted=json.loads(output.read_text());maximum=0
   for before,after in zip(baseline,shifted):
    assert before['fixture']==after['fixture']
    for kind in ['denseSamples','printedLineSamples']:
     assert len(before[kind])==len(after[kind])
     for a,b in zip(before[kind],after[kind]):
      delta=[b['residualPhysicalPx'][i]-a['residualPhysicalPx'][i]for i in [0,1]];error=max(abs(delta[0]-dx),abs(delta[1]-dy));maximum=max(maximum,error)
   assert maximum<1e-8,maximum
   translations.append({'dxPhysicalPx':dx,'dyPhysicalPx':dy,'fixtures':len(baseline),'maxKnownTranslationErrorPx':maximum})
  negatives=[]
  for name in ['nonce','source','missing-job','omitted-frame']:
   def corrupt(files):
    audit=files['render-audit-z20-audit.json']
    if name=='nonce':audit['frame']['renderSourceId']='stale'
    elif name=='source':audit['frame']['sourceId']='stale'
    elif name=='missing-job':audit['completed']['records']=[]
    else:audit['frame']['omittedDraws']=1
   install(corrupt);r=invoke(evidence,output);assert r.returncode!=0,('accepted incomplete or stale evidence',name);negatives.append(name)
  return {'status':'PASS','knownTranslations':translations,'identityOrOmissionNegativesRejected':negatives}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path);parser.add_argument('--evidence-root',type=Path,default=HERE/'observed_android');args=parser.parse_args();result=run(args.evidence_root);text=json.dumps(result,indent=2,allow_nan=False);print(text)
 if args.output:args.output.write_text(text+'\n')
