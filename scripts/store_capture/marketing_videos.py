#!/usr/bin/env python3
"""Branded, native-footage marketing masters and App Store previews.

The editorial file retains screenshot matches from the capture audit. UI is
always decoded from screen recordings. Motion graphics and the original score
are generated here; neither product screens nor product actions are fabricated.
"""
import argparse, hashlib, json, math, re, subprocess, wave
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / 'ios/build/store-marketing'
FONT = ROOT / 'scripts/fonts'
WHITE = (235, 241, 232)
GREEN, AMBER, BLUE = (116,227,138), (244,161,42), (79,168,255)
CHAPTERS = ['KNOW THE GROUND', 'READ THE TERRAIN', 'PLAN THE MOVE', 'MOVE TOGETHER', 'MAKE IT YOURS']

def run(args):
    p = subprocess.run([str(x) for x in args], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    if p.returncode: raise RuntimeError(p.stderr.decode())

def probe(p):
    return json.loads(subprocess.check_output(['ffprobe','-v','error','-show_format','-show_streams','-of','json',str(p)]))

def font(name, size): return ImageFont.truetype(str(FONT/name), size)
def local(p): return ROOT / p

def soundtrack(seconds=120):
    """Original D-minor electronic score, composed at 120 BPM; no samples."""
    path = WORK / 'tacmap-original-score.wav'
    if path.exists(): return path
    sr=48000; n=int(seconds*sr); mix=np.zeros((n,2),np.float32); rng=np.random.default_rng(730)
    def add(at, s, gain=1, pan=0):
        i=int(at*sr); count=min(len(s), n-i)
        if count<=0:return
        mix[i:i+count,0] += s[:count]*gain*math.sqrt((1-pan)/2)
        mix[i:i+count,1] += s[:count]*gain*math.sqrt((1+pan)/2)
    def synth(note,dur,kind):
        t=np.arange(int(dur*sr),dtype=np.float32)/sr; f=440*2**((note-69)/12)
        if kind=='pad':
            env=np.minimum(t/.28,1)*np.minimum((dur-t)/.7,1)
            return (np.sin(2*np.pi*f*t)+.35*np.sin(2*np.pi*f*1.002*t)+.12*np.sin(2*np.pi*f*2*t))*env
        env=(1-np.exp(-t*250))*np.exp(-t*(6 if kind=='pluck' else 2.8))
        return (np.sin(2*np.pi*f*t)+.25*np.sin(2*np.pi*2*f*t)+.08*np.sin(2*np.pi*3*f*t))*env
    chords=[(50,57,62,65),(46,53,58,62),(48,55,60,64),(45,52,57,60)]
    for bar in range(math.ceil(seconds/2)):
        at=bar*2; notes=chords[(bar//4)%4]
        # A quieter middle phrase gives the collaboration story breathing room.
        energy=.72 if 52<=at<64 else 1
        for j,note in enumerate(notes):add(at,synth(note+12,2.6,'pad'),.033,[-.6,.6,-.3,.3][j])
        for beat in range(4):
            t=np.arange(int(.32*sr),dtype=np.float32)/sr
            phase=2*np.pi*(43*t+68*.024*(1-np.exp(-t/.024)))
            kick=np.sin(phase)*np.exp(-t*17)*(1-np.exp(-t*700))
            add(at+beat*.5,kick,.25*energy)
            add(at+beat*.5,synth(notes[0]-12,.45,'bass'),.14*energy)
            if beat in (1,3):
                t=np.arange(int(.16*sr),dtype=np.float32)/sr
                noise=rng.standard_normal(len(t)).astype(np.float32)
                noise=np.convolve(noise,np.ones(7)/7,'same').astype(np.float32)
                add(at+beat*.5,noise*np.exp(-t*30),.10*energy,.08)
        for step in range(8):
            note=notes[[0,2,1,3,2,1,3,2][step]]+24
            add(at+step*.25,synth(note,.65,'pluck'),(.046 if at<4 else .070)*energy,(-.45 if step%2 else .45))
            t=np.arange(int(.045*sr),dtype=np.float32)/sr
            noise=rng.standard_normal(len(t)).astype(np.float32)
            add(at+step*.25,noise*np.exp(-t*100),.008*energy,(-.7 if step%2 else .7))
    # Musical delays/reverb, derived entirely from the synthesized performance.
    dry=mix.copy()
    for delay,gain in [(.1875,.18),(.375,.11),(.75,.06)]:
        k=int(sr*delay);mix[k:]+=dry[:-k,::-1]*gain
    mix=np.tanh(mix*1.2);mix*=.82/max(np.max(np.abs(mix)),.001)
    fade=int(sr*.6);mix[:fade]*=np.linspace(0,1,fade)[:,None]
    fade=int(sr*2);mix[-fade:]*=np.linspace(1,0,fade)[:,None]
    with wave.open(str(path),'wb') as w:
        w.setnchannels(2);w.setsampwidth(2);w.setframerate(sr);w.writeframes((mix*32767).astype('<i2').tobytes())
    return path

def clean_clip(beat, size):
    w,h=size; key=hashlib.sha256(json.dumps([beat['source'],beat['matched_frame'],beat.get('preroll',.5),beat['duration'],size]).encode()).hexdigest()[:16]
    beat['capture_start']=max(0,beat['matched_frame']-beat.get('preroll',.5))
    out=WORK/'clean'/f'{key}.mp4';out.parent.mkdir(parents=True,exist_ok=True)
    if out.exists():return out
    # Native recordings have sparse idle frames and unreliable seek indexes.
    # Normalize cadence BEFORE trimming, and decode from the beginning.
    at=max(0,beat['matched_frame']-beat.get('preroll',.5)); dur=beat['duration'];beat['capture_start']=at
    run(['ffmpeg','-y','-v','error','-i',local(beat['source']),'-vf',
         f'fps=30,trim=start={at}:duration={dur},setpts=PTS-STARTPTS,scale={w}:{h},setsar=1',
         '-t',dur,'-an','-c:v','libx264','-preset','veryfast','-crf','17','-pix_fmt','yuv420p',out])
    # Verify the clean footage, before the marketing overlays are added.
    raw=subprocess.check_output(['ffmpeg','-v','error','-ss',str(dur/2),'-i',str(out),'-vf','scale=64:128,format=gray','-frames:v','1','-f','rawvideo','-'])
    frame=np.frombuffer(raw,np.uint8).reshape(128,64).astype(np.int16)
    target=np.asarray(Image.open(local(beat['shot'])).convert('L').resize((64,128))).astype(np.int16)
    error=float(np.abs(frame-target).mean());beat['render_error']=error
    if error>18:raise ValueError(f"Wrong native scene: {beat['scene']} {error:.2f}")
    out.with_suffix('.json').write_text(json.dumps({'render_error':error}))
    return out

def layer(path,size,draw):
    im=Image.new('RGBA',size);draw(ImageDraw.Draw(im));im.save(path);return path

def wide_layers(beat, index, total, start):
    folder=WORK/'layers'/f"wide-{index}-{beat['platform']}";folder.mkdir(parents=True,exist_ok=True)
    accent=tuple(beat['accent']); headline=beat['headline'].split('\n')
    def base(d):
        for x in range(0,1920,80):d.line((x,0,x,1080),fill=(116,227,138,14))
        for y in range(0,1080,80):d.line((0,y,1920,y),fill=(116,227,138,14))
        d.text((96,48),'TacMap',font=font('Archivo-ExtraBold.ttf',34),fill=WHITE+(255,))
        d.text((270,59),f"{beat['chapter']+1:02d} / {CHAPTERS[beat['chapter']]}",font=font('JetBrainsMono-Regular.ttf',17),fill=accent+(255,))
        d.text((1331,25),'TacMap / '+beat['platform'],font=font('JetBrainsMono-Regular.ttf',17),fill=WHITE+(200,))
        # Editorial focus window contains only a crop of the actual native UI.
        d.text((96,490),beat['feature'].upper(),font=font('JetBrainsMono-Regular.ttf',17),fill=accent+(255,))
        d.rectangle((95,529,1127,922),outline=accent+(75,),width=1)
        for x,y,dx,dy in [(96,530,1,1),(1126,530,-1,1),(96,921,1,-1),(1126,921,-1,-1)]:
            d.line((x,y,x+dx*24,y),fill=accent+(255,),width=2);d.line((x,y,x,y+dy*24),fill=accent+(255,),width=2)
        for j,name in enumerate(['GROUND','TERRAIN','PLAN','TEAM','CONTROL']):
            x=96+j*216;c=accent if j==beat['chapter'] else (111,126,114)
            d.line((x,981,x+187,981),fill=c+(130,),width=2)
            d.text((x,1000),f'{j+1:02d} {name}',font=font('JetBrainsMono-Regular.ttf',16),fill=c+(255,))
        d.text((1770,1035),f'{index+1:02d}/{total:02d}',font=font('JetBrainsMono-Regular.ttf',15),fill=accent+(210,))
    paths=[layer(folder/'base.png',(1920,1080),base)]
    for j,line in enumerate(headline):
        def draw(d,line=line,j=j):d.text((0,0),line,font=font('Archivo-ExtraBold.ttf',108),fill=(WHITE if j==0 else accent)+(255,),stroke_width=0)
        paths.append(layer(folder/f'line-{j}.png',(1130,145),draw))
    paths.append(layer(folder/'support.png',(1150,80),lambda d:d.text((0,0),beat['support'],font=font('Archivo-Medium.ttf',29),fill=WHITE+(230,))))
    return paths

def portrait_layers(beat,size,index,total):
    w,h=size;folder=WORK/'layers'/f"portrait-{w}-{index}-{beat['platform']}";folder.mkdir(parents=True,exist_ok=True)
    accent=tuple(beat['accent']);hero=beat.get('hero',False);y=round(h*beat.get('caption_y',.20));height=round(w*(.255 if hero else .17))
    # Lower weather captions leave the advisory and native Sun/Moon table visible.
    if y+height>h*.97:y=round(h*.97-height)
    shade=Image.new('RGBA',(w,height));arr=np.zeros((height,w,4),np.uint8);arr[:,:,:3]=(8,12,9)
    edge=np.minimum(np.arange(height)/max(height*.12,1),(height-1-np.arange(height))/max(height*.12,1));arr[:,:,3]=(np.clip(edge,0,1)*225).astype(np.uint8)[:,None]
    Image.fromarray(arr).save(folder/'shade.png')
    def labels(d):
        d.line((w*.06,8,w*.14,8),fill=accent+(255,),width=max(2,int(w*.003)))
        d.text((w*.17,0),('TACMAP / '+CHAPTERS[beat['chapter']]) if hero else beat['feature'].upper(),font=font('JetBrainsMono-Regular.ttf',round(w*.019)),fill=accent+(255,))
        copy=beat['headline'].replace('\n',' ') if hero else beat['short']
        sizefont=round(w*(.066 if hero else .051));f=font('Archivo-ExtraBold.ttf',sizefont)
        while d.textlength(copy,font=f)>w*.88:sizefont-=1;f=font('Archivo-ExtraBold.ttf',sizefont)
        d.text((w*.06,w*.04),copy,font=f,fill=WHITE+(255,))
        support=beat['support'] if hero else beat.get('mini',beat['support'])
        fs=round(w*.028);f=font('Archivo-Medium.ttf',fs)
        while d.textlength(support,font=f)>w*.88:fs-=1;f=font('Archivo-Medium.ttf',fs)
        d.text((w*.06,w*(.126 if hero else .105)),support,font=f,fill=WHITE+(220,))
    layer(folder/'labels.png',(w,height),labels)
    return [folder/'shade.png',folder/'labels.png'],y,height

def encode_options(level,bitrate):
    return ['-c:v','libx264','-preset','fast','-profile:v','high','-level:v',level,'-b:v',bitrate,'-minrate',bitrate,'-maxrate',bitrate,'-bufsize','22M','-x264-params','nal-hrd=cbr:force-cfr=1','-pix_fmt','yuv420p','-r','30','-video_track_timescale','30000']

def render_scene(beat, cfg, i, total, elapsed, wide):
    size=cfg['native_size'];clean=clean_clip(beat,size)
    if 'render_error' not in beat:beat['render_error']=json.loads(clean.with_suffix('.json').read_text())['render_error']
    dur=beat['duration'];out=WORK/'edited'/f"{cfg['name']}-{i}.mp4";out.parent.mkdir(parents=True,exist_ok=True)
    args=['ffmpeg','-y','-v','error','-i',clean]; filters=[]
    def png(p):
        args.extend(['-loop','1','-i',p])
    if wide:
        paths=wide_layers(beat,i,total,elapsed)
        for p in paths:png(p)
        w,h=size;cropw=int(w*.94)//2*2;croph=int(cropw*392/1030)//2*2;cx=(w-cropw)//2;cy=min(h-croph,max(0,int(h*beat.get('focus',.2))))//2*2
        # Actual app fills the backdrop, full UI card, and the readable focus crop.
        phoneh=968;phonew=int(w*phoneh/h)//2*2;px=1330+(446-phonew)//2
        filters=[f'[0:v]split=3[bg][phone][detail]',
          '[bg]scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,gblur=sigma=28,eq=brightness=-0.32:saturation=0.45,drawbox=color=0x080e09@0.50:t=fill[back]',
          f'[phone]scale={phonew}:{phoneh},pad=iw+4:ih+4:2:2:color=0x{bytes(beat["accent"]).hex()}[p]',
          f'[detail]crop={cropw}:{croph}:{cx}:{cy},scale=1100:420,zoompan=z=\'1.025+0.015*on/{dur*30}\':x=\'(iw-iw/zoom)/2\':y=\'(ih-ih/zoom)/2\':d=1:s=1030x392:fps=30[d]',
          f'[back][p]overlay=x=\'{px}+24*pow(max(0,1-t/.5),3)\':y=54[v1]',
          '[v1][d]overlay=96:530[v2]', '[v2][1:v]overlay=0:0[v3]']
        label='v3'
        # A staggered reveal gives each benefit statement a clear rhythm.
        for j in range(len(paths)-2):
            idx=j+2;delay=.10+j*.12
            filters.append(f'[{idx}:v]format=rgba,fade=t=in:st={delay}:d=0.25:alpha=1[txt{j}]')
            filters.append(f'[{label}][txt{j}]overlay=x=\'96-34*pow(max(0,1-(t-{delay})/.5),3)\':y={155+j*119}[v{j+4}]')
            label=f'v{j+4}'
        idx=len(paths);filters.append(f'[{idx}:v]format=rgba,fade=t=in:st=0.38:d=0.3:alpha=1[sub]')
        filters.append(f'[{label}][sub]overlay=96:435[vtext]');label='vtext'
        filters.append(f'[{label}]drawbox=x=96:y=960:w=1030:h=2:color=0x74e38a@0.16:t=fill,drawbox=x=96:y=960:w=1030*{(elapsed+dur)/cfg["duration"]}:h=2:color=0x74e38a@.8:t=fill[vend]')
    else:
        paths,y,height=portrait_layers(beat,size,i,total)
        for p in paths:png(p)
        filters=[f'[1:v]format=rgba,fade=t=in:st=0:d=0.22:alpha=1[shade]',
          f'[2:v]format=rgba,fade=t=in:st=0.1:d=0.25:alpha=1[labels]',
          f'[0:v][shade]overlay=0:{y}[v1]',
          f'[v1][labels]overlay=x=\'-24*pow(max(0,1-t/.45),3)\':y={y+round(size[0]*.028)}[vend]']
    # Short dip dissolves smooth the cuts without pretending an app action occurred.
    intro='fade=t=in:st=0:d=0.10,' if i>0 else ''
    filters.append(f'[vend]{intro}fade=t=out:st={dur-.10}:d=0.10,format=yuv420p[out]')
    args+=['-filter_complex',';'.join(filters),'-map','[out]','-t',dur,'-an']+encode_options('4.0' if not wide else '4.2','11M' if not wide else '10M')+[out]
    run(args);return out

def render(cfg,test=False,mux_only=False):
    wide=cfg['format']=='landscape';beats=cfg['beats'];duration=cfg['duration'];elapsed=0;chunks=[]
    if test:beats=beats[:1];duration=beats[0]['duration']
    for i,beat in enumerate(beats):
        print(f"{cfg['name']} {i+1}/{len(beats)}: {beat['scene']}",flush=True)
        if mux_only:
            chunk=WORK/'edited'/f"{cfg['name']}-{i}.mp4"
            assert chunk.exists(),chunk
            clean=clean_clip(beat,cfg['native_size'])
            beat['render_error']=json.loads(clean.with_suffix('.json').read_text())['render_error']
        else:chunk=render_scene(beat,cfg,i,len(beats),elapsed,wide)
        chunks.append(chunk);beat['starts_at']=elapsed;elapsed+=beat['duration']
    concat=WORK/f"{cfg['name']}-concat.txt";concat.write_text(''.join(f"file '{p}'\n" for p in chunks))
    out=local(cfg['output']) if not test else WORK/f"{cfg['name']}-test.mp4";out.parent.mkdir(parents=True,exist_ok=True)
    score=soundtrack();offset=cfg.get('music_offset',0)
    run(['ffmpeg','-y','-v','error','-f','concat','-safe','0','-i',concat,'-ss',offset,'-i',score,'-t',duration,'-map','0:v','-map','1:a','-c:v','copy','-af',f'atrim=duration={duration},afade=t=in:d=0.25,afade=t=out:st={duration-1.2}:d=1.16,loudnorm=I=-16:TP=-1.5:LRA=8,aresample=48000,atrim=duration={duration-.08},asetpts=PTS-STARTPTS','-c:a','aac_at','-aac_at_mode','cbr','-b:a','256k','-ar','48000','-ac','2','-movflags','+faststart',out])
    p=probe(out);assert float(p['format']['duration'])==duration,(out,p['format']['duration'],duration)
    measured=subprocess.run(['ffmpeg','-v','info','-i',str(out),'-vn','-af','loudnorm=I=-16:TP=-1.5:LRA=8:print_format=json','-f','null','-'],capture_output=True,text=True,check=True)
    audio=json.loads(re.search(r'\{\s*"input_i".*?\}',measured.stderr,re.S).group())
    loud,peak=float(audio['input_i']),float(audio['input_tp'])
    assert -18<loud<-14 and peak<-.2,(out,loud,peak)
    meta={'output':out.name,'format':cfg['format'],'source_commit':'4e406f5','version':'3.0.0 (73)',
          'style':'Animated benefit headlines, native UI focus crops (masters), pulse score, dip dissolves',
          'music':{'title':'TacMap — Ground / Move','composition':'Original synthesizer composition; generated locally without third-party audio samples','bpm':120,'target_lufs':-16},
          'audio_quality':{'integrated_lufs':loud,'true_peak_dbtp':peak,'measurement':'EBU R128 / ffmpeg loudnorm'},'encoding':p,'scenes':beats}
    out.with_suffix('.json').write_text(json.dumps(meta,indent=2)+'\n');print(out,flush=True)

def main():
    a=argparse.ArgumentParser();a.add_argument('editorial');a.add_argument('--test',action='store_true');a.add_argument('--mux-only',action='store_true');args=a.parse_args()
    WORK.mkdir(parents=True,exist_ok=True);render(json.loads(Path(args.editorial).read_text()),args.test,args.mux_only)
if __name__=='__main__':main()
