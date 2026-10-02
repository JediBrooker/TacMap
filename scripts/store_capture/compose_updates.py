#!/usr/bin/env python3
"""Compose current PDF/calibration slides with the existing store theme."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import store_screenshots as ss
ROOT=Path(__file__).resolve().parents[2]
PROFILES={'iphone-6.9':(1320,2868,'ios/iphone-6.9'),'ipad-13':(2064,2752,'ios/ipad-13'),'android-phone':(1080,1920,'android/phone'),'android-tablet':(1440,2560,'android/tablet')}
SLIDES=[
('07-import-export','import-export','07 · IMPORT & EXPORT','In and out, anywhere','Import PDF maps and offline tiles. Share mission objects and recorded GPX tracks.','blue'),
('10-pdfmap','pdf-hero','10 · OFFLINE MAPS','Bring your own map','Use a GeoPDF offline, or calibrate a plain PDF to your live grid.','amber'),
('11-calibration','calibration-fit','11 · PDF CALIBRATION','Put any map on the grid','Match known points across the sheet. Review the measured fit before finishing.','amber'),
('12-calibration-entry','calibration-entry','12 · COORDINATE ENTRY','Match the printed grid','Enter known MGRS references using the datum printed on your map.','amber'),
('13-map-library','map-library','13 · SAVED MAPS','Keep your maps ready','Retain imported maps and calibrations. Switch back to the map you need.','green')]
def main():
 profile=sys.argv[1];raw=Path(sys.argv[2]);w,h,target=PROFILES[profile];out=ROOT/'docs/store'/target
 for name,src,eyebrow,headline,subcaption,accent in SLIDES:
  ss.render(dict(src=src+'.png',eyebrow=eyebrow,headline=headline,subcaption=subcaption,accent=accent),w,h,str(raw),str(out/(name+'.png')))
  print(out/(name+'.png'))
if __name__=='__main__':main()
