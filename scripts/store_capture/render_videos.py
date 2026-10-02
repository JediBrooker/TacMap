#!/usr/bin/env python3
"""Render native-app store videos from capture markers and an editorial JSON.
Uses screenshot matching to locate each actual scene in its screen recording;
never synthesizes a product screen. Captions use the existing store font/palette.
"""
import json,sys,subprocess,tempfile
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[2]
def run(args):
 r=subprocess.run(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
 if r.returncode:raise RuntimeError(r.stderr.decode())
def probe(path):return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(path)]))
def samples(path):
 data=subprocess.check_output(['ffmpeg','-v','error','-i',str(path),'-vf','fps=2,scale=64:128,format=gray','-f','rawvideo','-'])
 return np.frombuffer(data,dtype=np.uint8).reshape((-1,128,64)).astype(np.int16)
def local(path):
 p=Path(path);return p if p.is_absolute() else ROOT/p
def main():
 cfg=json.load(open(sys.argv[1]));w,h=cfg['size'];out=local(cfg['output']);out.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='tacmap-video-') as tmp:
  tmp=Path(tmp);cache={};chunks=[];evidence=[]
  for i,b in enumerate(cfg['beats']):
   video=local(b['video']);shot=local(b['shot']);dur=b['duration']
   if str(video) not in cache:cache[str(video)]=samples(video)
   frames=cache[str(video)];target=np.asarray(Image.open(shot).convert('L').resize((64,128))).astype(np.int16)
   scores=np.abs(frames-target).mean(axis=(1,2));window=max(1,int(np.ceil(dur*2)));rolling=np.convolve(scores,np.ones(window)/window,mode='valid');best=int(np.argmin(rolling));at=best/2
   # A match below 18 is required, so a missing/loading/incorrect screen cannot silently enter the edit.
   if rolling[best]>18:raise ValueError(f'No matching app footage for {shot.name}: {scores[best]:.1f}')
   print(f'{i+1}: {shot.stem} @ {at:.2f}s match {scores[best]:.2f}',flush=True)
   caption=Image.new('RGBA',(w,h));d=ImageDraw.Draw(caption);font=ImageFont.truetype(str(ROOT/'scripts/fonts/Archivo-ExtraBold.ttf'),int(w*.041))
   lines=b['caption'].split('\n');line_h=int(w*.057);pad=int(w*.023);tw=max(d.textlength(x,font=font) for x in lines);box_h=line_h*len(lines)+pad*2;y=int(h*b.get('caption_y',.20));x=int((w-tw-pad*2)/2)
   d.rounded_rectangle((x,y,w-x,y+box_h),radius=int(w*.02),fill=(8,11,8,235),outline=tuple(b.get('accent',[116,227,138]))+(230,),width=2)
   for j,line in enumerate(lines):d.text((w/2,y+pad+j*line_h),line,font=font,fill=(233,240,232,255),anchor='mt')
   overlay=tmp/f'{i}.png';caption.save(overlay);chunk=tmp/f'{i}.mp4';chunks.append(chunk)
   run(['ffmpeg','-y','-v','error','-i',str(video),'-loop','1','-i',str(overlay),'-filter_complex',f'[0:v]fps=30,trim=start={at}:duration={dur},setpts=PTS-STARTPTS,scale={w}:{h},setsar=1[v];[v][1:v]overlay=0:0,format=yuv420p[out]','-map','[out]','-t',str(dur),'-an','-c:v','libx264','-preset','fast','-profile:v','high','-level:v',cfg.get('level','4.0'),'-b:v',cfg.get('bitrate','11M'),'-minrate',cfg.get('bitrate','11M'),'-maxrate',cfg.get('bitrate','11M'),'-bufsize','22M','-x264-params','nal-hrd=cbr:force-cfr=1','-video_track_timescale','30000',str(chunk)])
   decoded=subprocess.check_output(['ffmpeg','-v','error','-ss',str(dur/2),'-i',str(chunk),'-vf','scale=64:128,format=gray','-frames:v','1','-f','rawvideo','-'])
   rendered=np.frombuffer(decoded,dtype=np.uint8).reshape((128,64)).astype(np.int16)
   mask=np.ones((128,64),dtype=bool);cy=int(128*b.get('caption_y',.20));mask[max(0,cy-3):min(128,cy+14),:]=False
   render_error=float(np.abs(rendered-target)[mask].mean())
   if render_error>18:raise ValueError(f'Encoded scene differs from its captured UI: {shot.name}: {render_error:.1f}')
   evidence.append(dict(scene=shot.stem,source=str(video),matched_frame=best/2,match_error=float(scores[best]),render_error=render_error,duration=dur,caption=b['caption']))
  concat=tmp/'clips.txt';concat.write_text(''.join("file '"+str(c)+"'\n" for c in chunks));duration=sum(b['duration'] for b in cfg['beats'])
  run(['ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',str(concat),'-f','lavfi','-i','anullsrc=channel_layout=stereo:sample_rate=48000','-t',str(duration),'-map','0:v','-map','1:a','-c:v','copy','-af',f'atrim=duration={duration-.04}','-c:a','aac_at','-aac_at_mode','cbr','-b:a','256k','-ar','48000','-ac','2','-movflags','+faststart',str(out)])
  p=probe(out);v=next(x for x in p['streams'] if x['codec_type']=='video');a=next(x for x in p['streams'] if x['codec_type']=='audio')
  assert abs(float(p['format']['duration'])-duration)<.05
  assert(v['width'],v['height'])==(w,h) and v['avg_frame_rate']=='30/1' and v['codec_name']=='h264' and a['sample_rate']=='48000' and a['channels']==2
  out.with_suffix('.json').write_text(json.dumps(dict(output=out.name,encoding=p,scenes=evidence),indent=2)+'\n')
  print(out,flush=True)
if __name__=='__main__':main()
