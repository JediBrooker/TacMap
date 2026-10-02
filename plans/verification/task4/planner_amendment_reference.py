"""Counterfactual old/new canonical bounds for the actual Canberra job.
Uses the independent contractual PROJ generator, never native rendering code.
Outputs whole-job dense norms; actual screenshot viewport samples are separate.
"""
import json,pathlib,importlib.util,numpy as np,math
R=pathlib.Path(__file__).resolve().parents[3];S=pathlib.Path(__file__).parent;sp=importlib.util.spec_from_file_location('tile',R/'scripts/gen_pdf_tile_render.py');t=importlib.util.module_from_spec(sp);sp.loader.exec_module(t);M=json.load(open(R/'plans/verification/task4/fixture_manifest.json'));e=next(e for e in M['entries']if e['id']=='cbr50k_iso');ex=e['expectedReference'];G=json.load(open(R/'testdata/pdf_georef.json'));datums={d['id']:d for d in G['datums']['table']};crop=next(x for x in G['sheets']if x['id']==e['id'])['expected']['crop'];g=t.Georef(e['id'],ex['crs'],ex['datum'],ex['affine'],crop,datums);fp=t.Footprint(g,e.get('cropBox')or e['mediaBox']);out=[]
for tol in [.25,.0625,.03125,.015625]:
 t.WARP_MAX_ERR=tol;problems=[];root,depth,leaves,dropped=t.plan_warp(g,fp,(15,29954,19823,1,1),768,problems,'observed-ios-canberra');maxerr=0
 for leaf in leaves:
  if leaf['coverage']=='OUTSIDE':continue
  l,top,r,b=leaf['rect'];a=leaf['pageToPx']
  for u in np.linspace(l,r,41):
   for v in np.linspace(top,b,41):
    la,lo=t.job_px_to_latlon(15,29954,19823,768,u,v);p=g.to_page(la,lo);dx=a[0]*p[0]+a[1]*p[1]+a[2]-u;dy=a[3]*p[0]+a[4]*p[1]+a[5]-v;maxerr=max(maxerr,math.hypot(dx,dy)*32)
 out.append({'proposedCanonicalErrorBoundPx':tol,'emittedCells':len(leaves),'maxCellPlannerCanonicalErrorPx':max(l['errorPx']for l in leaves),'maxDenseWholeJobPhysicalErrorPxAt32x':maxerr,'problems':problems})
print(json.dumps(out,indent=2));(S/'canberra-tolerance-proposal.json').write_text(json.dumps(out,indent=2)+'\n')
