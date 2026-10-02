import base64,copy,json,uuid
from audit_chunks import reconstruct

def run():
    raw=json.dumps({'complete':True,'sample':'geometry '*140}).encode();rid=str(uuid.uuid4());events=[]
    pieces=[raw[i:i+600]for i in range(0,len(raw),600)]
    for i,data in enumerate(pieces):events.append({'processID':42,'timestamp':'2026-10-02 12:00:00+1000','eventMessage':f'TASK4_CHUNK FRAME {rid} {i} {len(pieces)} {len(raw)} '+base64.b64encode(data).decode()})
    result=reconstruct(events,{42});assert len(result['records'])==1 and not result['missing'];assert result['records'][0]['value']['complete'];negative=[]
    for case in ['missing','duplicate','inconsistent-total','inconsistent-size','bad-base64','wrong-size','nonfinite','invalid-utf8','omitted']:
        bad=copy.deepcopy(events)
        if case=='missing':bad.pop()
        elif case=='duplicate':bad.append(copy.deepcopy(bad[0]))
        elif case in ['inconsistent-total','inconsistent-size','wrong-size']:
            for i in ([0]if case!='wrong-size'else range(len(bad))):
                fields=bad[i]['eventMessage'].split(' ');n=4 if case=='inconsistent-total'else 5;fields[n]=str(int(fields[n])+1);bad[i]['eventMessage']=' '.join(fields)
        elif case=='bad-base64':bad[0]['eventMessage']=bad[0]['eventMessage'].rsplit(' ',1)[0]+' !'
        elif case in ['nonfinite','invalid-utf8']:
            data=b'{"error":NaN}'if case=='nonfinite'else b'{"error":"\xff"}';bad=[{'processID':42,'timestamp':events[0]['timestamp'],'eventMessage':f'TASK4_CHUNK FRAME {rid} 0 1 {len(data)} '+base64.b64encode(data).decode()}]
        else:bad.append({'processID':42,'eventMessage':'TASK4_OMITTED FRAME'})
        r=reconstruct(bad,{42});assert r['missing'],case
        if case!='omitted':assert not r['records'],case
        negative.append(case)
    assert reconstruct(events,{43})=={'records':[],'missing':[]}
    return {'status':'PASS','completeMultiChunkRecordVerified':True,'malformedOrIncompleteCasesRejected':negative,'staleProcessExcluded':True}
if __name__=='__main__':print(json.dumps(run(),indent=2))
