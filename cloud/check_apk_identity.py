#!/usr/bin/env python3
"""Fail closed on actual compiled APK identity and FileProvider collisions."""
import argparse,hashlib,json,os,pathlib,re,subprocess

def require(condition,message):
    if not condition:raise ValueError(message)

def inspect_text(badging,tree,identity,variant):
    production=identity['packageName']
    package=identity['developmentPackageName'] if variant=='development' else production
    require(package!=production or variant=='release','Development application ID is not isolated.')
    match=re.search(r"package: name='([^']+)'",badging)
    require(match and match[1]==package,'Actual APK package differs from the selected variant.')
    require(('application-debuggable' in badging)==(variant=='development'),'Unexpected APK debuggable flag.')
    launcher=re.search(r"launchable-activity: name='([^']+)'",badging)
    require(launcher and launcher[1]==identity['launcherActivity'],'Unexpected launcher component class.')
    providers={};lines=tree.splitlines()
    for index,line in enumerate(lines):
        if not line.strip().startswith('E: provider '):continue
        indent=len(line)-len(line.lstrip());block=[]
        for child in lines[index+1:]:
            if len(child)-len(child.lstrip())<=indent:break
            block.append(child)
        text='\n'.join(block)
        def value(name):
            found=re.search(r'android:'+name+r'[^=]*="([^"]+)"',text)
            return found[1] if found else None
        authorities=value('authorities')
        if not authorities:continue
        for authority in authorities.split(';'):
            require(authority not in providers,'Duplicate provider authority.')
            if variant=='development':
                require(not (authority==production or authority.startswith(production+'.') and not authority.startswith(package+'.')),'Development provider occupies a production authority.')
            providers[authority]=text
    required={package+suffix for suffix in identity['fileProviderSuffixes']}
    require(required.issubset(providers),'Expected FileProvider authority is missing.')
    for authority in required:
        text=providers[authority]
        require('androidx.core.content.FileProvider' in text,'Unexpected FileProvider class.')
        require(re.search(r'android:exported[^=]*=(?:\(type 0x12\))?(?:0x0\b|false\b)',text),'FileProvider is not private.')
        require(re.search(r'android:grantUriPermissions[^=]*=(?:\(type 0x12\))?(?:0xffffffff\b|true\b)',text),'FileProvider URI grants are missing.')
    return {'packageName':package,'debuggable':variant=='development','launcherActivity':launcher[1],'providerAuthorities':sorted(providers)}

def inspect(apk,identity,variant):
    sdk=pathlib.Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or '')
    aapt=sdk/'build-tools/36.0.0'/('aapt2.exe' if os.name=='nt' else 'aapt2')
    def run(*args):
        result=subprocess.run([str(aapt),*map(str,args)],capture_output=True,text=True,encoding='utf8',errors='replace')
        require(result.returncode==0,'APK inspection failed; no signing is permitted.')
        return result.stdout
    proof=inspect_text(run('dump','badging',apk),run('dump','xmltree',apk,'--file','AndroidManifest.xml'),identity,variant)
    with apk.open('rb') as source:proof['sha256']=hashlib.file_digest(source,'sha256').hexdigest()
    return proof

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=pathlib.Path);p.add_argument('--variant',choices=['development','release'],required=True)
    p.add_argument('--identity',type=pathlib.Path,default=pathlib.Path(__file__).resolve().parent.parent/'release/signing-identity.json')
    a=p.parse_args()
    try:print(json.dumps(inspect(a.apk,json.loads(a.identity.read_text(encoding='utf8')),a.variant),sort_keys=True))
    except Exception:raise SystemExit('APK variant/provider validation failed; delivery refused.') from None
