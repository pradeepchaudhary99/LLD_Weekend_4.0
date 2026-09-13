#!/usr/bin/env python3
"""Compile/run each pattern in all languages and check independently specified output."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import subprocess
import sys
from run import ROOT, LANGUAGES

EXPECTED = {
 'SimpleFactoryDesignPattern': 'SMS: Hello\nSlack: Hello\nWhatsApp: Hello\nSame instance: true\nUnknown notification type',
 'FactoryMethodDesignPattern': 'SMS: message\nWhatsApp: message\nPush: message',
 'BuilderDesignPattern': 'pradeep 23 Delhi 100.0',
 'Singleton': 'Same instance: true',
 'Abstract_FactoryDesign': 'linux button rendered\nlinux modal rendered\nlinux screen rendered',
 'DecoratorDesignPattern': 'SMS: Class starts at 1 PM',
 'AdapterDesignPattern': 'Legacy payment: 100\nThird-party payment: 200\nAmount must be positive',
 'ProxyDesignPattern': 'DB read: lesson\nJava\nCache hit: lesson\nJava\nDB read: lesson\nPatterns\nDB read: missing\nMissing key',
 'ObserverDesignPattern': 'Phone: 10\nTV: 10\nPhone: 20\nFloor: 3',
 'StrategyDesign': 'r1 -> A\nr2 -> B\nr3 -> B\nNo servers available',
 'StateDesignPattern': 'Playing\nPaused\nPlaying',
 'ATMMachineStateDesign': 'Insert card first\nCard inserted\nCard already inserted\nDispensing started\nAlready dispensing\nCannot cancel dispensing\nCash dispensed: 100\nCard ejected\nCard inserted\nCancelled\nCard ejected\nNothing to complete',
 'FileSystem_Node': 'hallo\nroot: 5\nlesson.txt\nCycle rejected\nDuplicate name\nInvalid offset',
 'FacadePatternDemo': 'Preparing Home Theater...\n\nTV is ON\nTV input set to HDMI\nSound System is ON\nVolume set to 20\nStreaming Device is ON\nPlaying movie: Interstellar\n\nEnjoy your movie!'
}

def check(language, lesson):
    result = subprocess.run([sys.executable, str(ROOT/'scripts/run.py'), language, 'DesignPatterns/'+lesson], cwd=ROOT, text=True, capture_output=True, timeout=180)
    output = result.stdout.strip()
    # dotnet build prints a summary before the lesson. Compare only after it.
    if language == 'C#' and 'Time Elapsed' in output:
        output = output.split('Time Elapsed', 1)[1].split('\n', 1)[-1].strip()
    ok = result.returncode == 0 and output == EXPECTED[lesson]
    return {'language':language, 'lesson':lesson, 'passed':ok, **({} if ok else {'stdout':result.stdout,'stderr':result.stderr,'expected':EXPECTED[lesson]})}

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--languages',nargs='+',choices=LANGUAGES,default=list(LANGUAGES))
    parser.add_argument('--report',type=Path)
    args=parser.parse_args()
    results=[]
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures=[pool.submit(check,lang,lesson) for lang in args.languages for lesson in EXPECTED]
        for future in as_completed(futures):
            try: result=future.result()
            except Exception as error: result={'passed':False,'error':str(error)}
            results.append(result)
            print(('PASS' if result['passed'] else 'FAIL'),result.get('language',''),result.get('lesson',''),flush=True)
            if not result['passed']: print(json.dumps(result,indent=2),flush=True)
    if args.report: args.report.write_text(json.dumps(results,indent=2)+'\n')
    print(f"{sum(r['passed'] for r in results)}/{len(results)} passed")
    sys.exit(0 if all(r['passed'] for r in results) else 1)
