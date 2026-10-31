# Palette-quantise RGBA PNGs (stdlib only): median cut -> indexed PNG with tRNS.
import sys, os, zlib, struct
def read(p):
    d = open(p,'rb').read(); assert d[:8]==b'\x89PNG\r\n\x1a\n'
    i=8; idat=b''; plte=b''; trns=b''
    while i<len(d):
        n,=struct.unpack('>I',d[i:i+4]); t=d[i+4:i+8]; c=d[i+8:i+8+n]; i+=12+n
        if t==b'IHDR': w,h,bd,ct,_,_,il=struct.unpack('>IIBBBBB',c)
        elif t==b'IDAT': idat+=c
        elif t==b'PLTE': plte=c
        elif t==b'tRNS': trns=c
    assert bd==8 and ct in (3,6) and il==0,(bd,ct,il)
    raw=zlib.decompress(idat); bpp=1 if ct==3 else 4; s=w*bpp; out=bytearray(); prev=bytearray(s); j=0
    for y in range(h):
        f=raw[j]; line=bytearray(raw[j+1:j+1+s]); j+=1+s
        for x in range(s):
            a=line[x-bpp] if x>=bpp else 0; b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+((a+b)>>1))&255
            elif f==4:
                pa=abs(b-c); pb=abs(a-c); pc=abs(a+b-2*c)
                pr=a if pa<=pb and pa<=pc else (b if pb<=pc else c)
                line[x]=(line[x]+pr)&255
        out+=line; prev=line
    if ct==3:
        o=bytearray()
        for v in out: o+=bytes(plte[v*3:v*3+3])+bytes([trns[v] if v<len(trns) else 255])
        out=o
    return w,h,bytes(out)
def chunk(t,c): 
    return struct.pack('>I',len(c))+t+c+struct.pack('>I',zlib.crc32(t+c)&0xffffffff)
def quant(px,k):
    cols={}
    for i in range(0,len(px),4):
        r,g,b,a=px[i:i+4]
        if a==0: r=g=b=0
        key=(r,g,b,a); cols[key]=cols.get(key,0)+1
    boxes=[list(cols.items())]
    while len(boxes)<k:
        boxes.sort(key=lambda bx:-(len(bx) if len(bx)>1 else 0)*0-sum(c for _,c in bx)*(max(max(k_[ch] for k_,_ in bx)-min(k_[ch] for k_,_ in bx) for ch in range(4))) if len(bx)>1 else 0)
        bx=boxes.pop(0)
        if len(bx)<2: boxes.append(bx); break
        ch=max(range(4),key=lambda c:max(k_[c] for k_,_ in bx)-min(k_[c] for k_,_ in bx))
        bx.sort(key=lambda kc:kc[0][ch]); tot=sum(c for _,c in bx); acc=0
        for m,(_,c) in enumerate(bx):
            acc+=c
            if acc>=tot/2: break
        m=min(max(m+1,1),len(bx)-1); boxes+= [bx[:m],bx[m:]]
    pal=[]; 
    for bx in boxes:
        t=sum(c for _,c in bx); pal.append(tuple(round(sum(k_[ch]*c for k_,c in bx)/t) for ch in range(4)))
    return pal
def encode(w,h,px,k):
    pal=quant(px,k); cache={}
    def idx(q):
        v=cache.get(q)
        if v is None:
            v=min(range(len(pal)),key=lambda i:(pal[i][0]-q[0])**2+(pal[i][1]-q[1])**2+(pal[i][2]-q[2])**2+(pal[i][3]-q[3])**2*1.0+0)
            cache[q]=v
        return v
    raw=bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            i=(y*w+x)*4; q=tuple(px[i:i+4]); 
            if q[3]==0: q=(0,0,0,0)
            raw.append(idx(q))
    best=None
    # filter 0 only; zlib max
    z=zlib.compress(bytes(raw),9)
    plte=b''.join(bytes(p[:3]) for p in pal); trns=bytes(p[3] for p in pal)
    o=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,3,0,0,0))+chunk(b'PLTE',plte)
    if any(p[3]!=255 for p in pal): o+=chunk(b'tRNS',trns)
    return o+chunk(b'IDAT',z)+chunk(b'IEND',b'')
if __name__=='__main__':
    k=int(sys.argv[1]); src=sys.argv[2]; dst=sys.argv[3]; os.makedirs(dst,exist_ok=True)
    a=b=skipped=0
    for n in sorted(os.listdir(src)):
        if not n.endswith('.png'): continue
        orig=os.path.getsize(os.path.join(src,n))
        try: w,h,px=read(os.path.join(src,n)); o=encode(w,h,px,k)
        except AssertionError: o=open(os.path.join(src,n),'rb').read(); skipped+=1  # interlaced or other layout: keep as is
        if len(o)>=orig: o=open(os.path.join(src,n),'rb').read()
        open(os.path.join(dst,n),'wb').write(o); a+=orig; b+=len(o)
    print(k,a,b,round(100*b/a),'%, kept as is:',skipped)
