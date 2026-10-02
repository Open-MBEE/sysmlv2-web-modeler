"""Read-only endpoint regression: tiny source must render in all supported formats."""
import json
import sys
import urllib.request
from urllib.error import HTTPError
base = sys.argv[1] if len(sys.argv)>1 else 'http://localhost:8088'
failed=[]
for fmt, marker in [('plantuml','@startuml'),('text','part def Vehicle'),('svg','<svg'),('puml','@startuml'),('txt','part def Vehicle')]:
    request=urllib.request.Request(base+'/renderText',data=json.dumps({'modelText':'package Demo { part def Vehicle { part wheel; } }','element':'Demo::Vehicle','format':fmt,'view':'TREE','style':'LR'}).encode(),headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(request,timeout=45) as response:
            output=response.read().decode()
        assert marker in output, 'Expected '+marker
        print(fmt+': PASS')
    except (HTTPError,AssertionError) as error:
        message=error.read().decode() if isinstance(error,HTTPError) else str(error)
        print(fmt+': FAIL '+message[:200]);failed.append(fmt)
if failed:raise SystemExit(1)
