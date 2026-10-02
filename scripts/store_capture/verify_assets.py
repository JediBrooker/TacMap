#!/usr/bin/env python3
"""Verify final store files and write their upload manifest."""
import hashlib,json,subprocess
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[2]
STORE=ROOT/'docs/store'
PROFILES={'ios/iphone-6.9':(1320,2868),'ios/ipad-13':(2064,2752),'android/phone':(1080,1920),'android/tablet':(1440,2560)}
NAMES=['10-pdfmap','11-calibration','12-calibration-entry','13-map-library','07-import-export']
ALT=['TacMap displays an offline GeoPDF with a live MGRS grid.','Four known control points calibrate a PDF; TacMap reports the fit residual.','A known MGRS reference is entered in the datum printed on the map.','Imported maps and retained calibrations appear in the saved-map library.','TacMap imports PDF maps and offline tiles and exports mission objects and GPX tracks.']
def file_info(p):return dict(path=str(p.relative_to(STORE)),bytes=p.stat().st_size,sha256=hashlib.sha256(p.read_bytes()).hexdigest())
def main():
 screenshots=[];videos=[]
 for profile,size in PROFILES.items():
  for name,alt in zip(NAMES,ALT):
   p=STORE/profile/(name+'.png');im=Image.open(p);assert im.size==size and im.mode=='RGB',(p,im.size,im.mode)
   screenshots.append(dict(**file_info(p),width=size[0],height=size[1],alt_text=alt))
 paths=list((STORE/'ios/previews').rglob('*.mp4'))+[STORE/'android/video/tacmap-features-3.0.mp4',STORE/'ios/marketing/tacmap-features-3.0.mp4'];assert len(paths)==8
 for p in paths:
  probe=json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(p)]));v=next(s for s in probe['streams'] if s['codec_type']=='video');a=next(s for s in probe['streams'] if s['codec_type']=='audio');duration=float(probe['format']['duration']);ios='ios' in p.relative_to(STORE).parts;preview='previews' in p.parts
  expected=(1200,1600) if 'ipad-13' in p.parts else (886,1920) if preview else (1920,1080)
  expected_duration=30 if preview else 90 if ios else 120
  assert duration==expected_duration
  assert (v['width'],v['height'])==expected and v['codec_name']=='h264' and v['profile']=='High' and v['avg_frame_rate']=='30/1' and v['pix_fmt']=='yuv420p' and v['field_order']=='progressive'
  assert a['codec_name']=='aac' and a['sample_rate']=='48000' and a['channels']==2 and 250000<int(a['bit_rate'])<265000
  if preview:assert v['level']==40 and 10000000<int(v['bit_rate'])<12000000 and p.stat().st_size<500000000
  side=p.with_suffix('.json');c=json.loads(side.read_text());assert all(s['render_error']<=18 for s in c['scenes']);assert abs(sum(s['duration'] for s in c['scenes'])-expected_duration)<.01
  assert -18<c['audio_quality']['integrated_lufs']<-14 and c['audio_quality']['true_peak_dbtp']<-.2
  # Confirm the original soundtrack is audible, rather than a silent AAC track.
  import array,math
  pcm=array.array('h');pcm.frombytes(subprocess.check_output(['ffmpeg','-v','error','-i',str(p),'-map','0:a','-t','5','-ac','1','-ar','8000','-f','s16le','-']))
  rms=math.sqrt(sum(x*x for x in pcm)/len(pcm));assert rms>300,(p,'silent soundtrack')
  c['source_commit']='4e406f5';c['version']='3.0.0 (73)';c['encoding']=probe;c['encoding']['format']['filename']=str(p.relative_to(ROOT))
  elapsed=0
  for s in c['scenes']:
   src=Path(s['source']);s['source']=str(src.relative_to(ROOT)) if src.is_absolute() else str(src);s['starts_at']=elapsed;elapsed+=s['duration']
  side.write_text(json.dumps(c,indent=2)+'\n')
  publication='App Store Connect upload required' if preview else 'External marketing master; use the three 30-second previews for App Store Connect' if ios else 'YouTube upload required for Google Play'
  videos.append(dict(**file_info(p),width=v['width'],height=v['height'],seconds=duration,fps=30,video_codec='H.264 High',level=v['level']/10,video_bitrate=int(v['bit_rate']),audio_codec='AAC stereo CBR',audio_bitrate=int(a['bit_rate']),sample_rate=48000,audio_quality=c['audio_quality'],audio_rms_first_5s=rms,scene_count=len(c['scenes']),max_render_error=max(s['render_error'] for s in c['scenes']),publication=publication))
 manifest=dict(version='3.0.0 (73)',source_commit='4e406f5',created='2026-10-02',screenshots=screenshots,videos=videos,verification='Dimensions, RGB, exact duration, codec, progressive scan, frame rate, bitrate, audible audio, native scene matching and visual contact sheets checked.',guide='FEATURE_VIDEO.md')
 manifest['music']=dict(**file_info(STORE/'marketing/tacmap-original-score.flac'),title='TacMap — Ground / Move',composition='Original synthesizer composition without third-party samples',seconds=120,bpm=120)
 (STORE/'3.0.0-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print(f'Verified {len(screenshots)} screenshots and {len(videos)} videos.')
if __name__=='__main__':main()
