"""Strict host reconstruction of bounded DEBUG audit chunks; no native code.
Missing, duplicate, stale-PID, inconsistent or malformed data never supplies
successful geometry. Legacy truncated JSON remains explicit missing evidence.
"""
import argparse,base64,json,pathlib,re

def strict_json(text):
    def reject(value):raise ValueError('nonfinite JSON: '+value)
    return json.loads(text,parse_constant=reject)

def reconstruct(events,process_ids):
    groups={};records=[];missing=[]
    for event in events:
        pid=event.get('processID');at=event.get('timestamp');m=event.get('eventMessage','')
        if pid not in process_ids:continue
        if m.startswith('TASK4_CHUNK '):
            try:
                _,kind,rid,index,total,size,encoded=m.split(' ',6)
                index,total,size=map(int,(index,total,size))
                if kind not in ['JOB','FRAME']or not re.fullmatch(r'[0-9A-Fa-f-]{36}',rid):raise ValueError('kind or ID')
                if not 0<=index<total<=4096 or not 0<size<=2*1024*1024:raise ValueError('caps')
                data=base64.b64decode(encoded,validate=True)
                if not 0<len(data)<=600:raise ValueError('chunk size')
            except (ValueError,TypeError)as e:
                missing.append({'reason':'invalidChunk','processID':pid});continue
            key=pid,rid;g=groups.setdefault(key,{'kind':kind,'total':total,'size':size,'chunks':{},'at':at,'invalid':False})
            if (kind,total,size)!=(g['kind'],g['total'],g['size'])or index in g['chunks']:g['invalid']=True
            g['chunks'][index]=data;g['at']=max(g['at'],at)
        elif m.startswith(('TASK4_JOB ','TASK4_FRAME ')):
            kind,payload=m.split(' ',1)
            try:value=strict_json(payload)
            except (ValueError,UnicodeError):missing.append({'reason':'priorTruncatedJSON','processID':pid});continue
            records.append({'kind':kind,'processID':pid,'timestamp':at,'value':value})
        elif m.startswith('TASK4_OMITTED '):missing.append({'reason':'explicitOmission','processID':pid})
    for (pid,rid),g in groups.items():
        try:
            if g['invalid']or set(g['chunks'])!=set(range(g['total'])):raise ValueError('missingDuplicateOrInconsistent')
            raw=b''.join(g['chunks'][i]for i in range(g['total']))
            if len(raw)!=g['size']:raise ValueError('wrongByteCount')
            value=strict_json(raw.decode('utf-8'))
            if not isinstance(value,dict):raise ValueError('notObject')
        except (ValueError,UnicodeError)as e:
            missing.append({'reason':str(e),'recordID':rid,'processID':pid});continue
        records.append({'kind':'TASK4_'+g['kind'],'processID':pid,'timestamp':g['at'],'recordID':rid,'chunks':g['total'],'bytes':g['size'],'value':value})
    return {'records':records,'missing':missing}

def stream_events(text):
    decoder=json.JSONDecoder();offset=0
    while True:
        start=text.find('{',offset)
        if start<0:return
        value,length=decoder.raw_decode(text[start:]);offset=start+length
        if not isinstance(value,dict):raise ValueError('invalid log event')
        yield value

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--log',type=pathlib.Path,required=True);p.add_argument('--camera-index',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args();ids={r['uiProcessID']for r in strict_json(a.camera_index.read_text())};result=reconstruct(stream_events(a.log.read_text()),ids);a.output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n');print('complete',len(result['records']),'missing',len(result['missing']))
