"""Crea motore/src/test/resources/sintetico.dng: DNG in stile Pixel (tessere lossless JPEG, GainMap, ForwardMatrix)."""
import numpy as np, struct, sys
sys.path.insert(0,'.')
from ljpeg_codifica import encode
W,H=320,256; TW,TH=64,64
rng=np.random.default_rng(5)
# scena lineare: gradiente + toppe colorate + cielo luminoso
y,x=np.mgrid[0:H,0:W]
scena=np.zeros((H,W,3))
scena[...]=(x/W)[...,None]*np.array([0.5,0.5,0.5])+0.02
scena[20:90,30:120]=[0.6,0.15,0.1]   # rosso
scena[120:200,40:110]=[0.1,0.5,0.15] # verde
scena[60:180,180:300]=[0.15,0.25,0.6] # blu
scena[0:20,:]=[3.0,3.0,3.0] # luce bruciata
neutro=np.array([0.5,1.0,0.62])
cam=scena*neutro  # fotocamera: guadagni diversi per canale
vign=1/(1+0.4*(((x-W/2)/(W/2))**2+((y-H/2)/(H/2))**2))
cam=cam*vign[...,None]
cfa=np.zeros((H,W)); pat=[0,1,1,2]
for r in range(2):
    for c in range(2):
        cfa[r::2,c::2]=cam[r::2,c::2,pat[r*2+c]]
black,white=64,1023
raw=np.clip(black+cfa*(white-black)+rng.normal(0,1.5,cfa.shape),0,white).astype(np.uint16)
# tessere: lossless JPEG 2 componenti, larghezza TW/2
tiles=[]
for ty in range(0,H,TH):
    for tx in range(0,W,TW):
        t=raw[ty:ty+TH,tx:tx+TW]
        tiles.append(encode(t.reshape(TH,TW//2,2),16,1))
# GainMap: 4 mappe (una per posizione del filtro), 5x5 nodi
def gainmap(top,left):
    pv=ph=5
    vals=[]
    for i in range(pv):
        for j in range(ph):
            yy=i/(pv-1); xx=j/(ph-1)
            vals.append(1+0.4*(((xx-0.5)*2)**2+((yy-0.5)*2)**2))
    p=struct.pack('>IIIIIIIIII',top,left,H,W,0,1,2,2,pv,ph)+struct.pack('>dddd',1/(pv-1),1/(ph-1),0,0)+struct.pack('>I',1)+struct.pack('>%df'%len(vals),*vals)
    return struct.pack('>IIII',9,0x01030000,0,len(p))+p
ops=struct.pack('>I',4)+b''.join(gainmap(t,l) for t in (0,1) for l in (0,1))
# --- scrittura TIFF ---
out=bytearray(b'II*\x00\x00\x00\x00\x00')
def add(b):
    global out
    while len(out)%2: out.append(0)
    o=len(out); out+=b; return o
T={'B':1,'A':2,'H':3,'I':4,'R':5,'U':7,'SR':10}
def ifd(entries):
    # entries: list of (tag,type,values)
    ents=[]
    for tag,typ,vals in sorted(entries):
        if typ=='A': data=vals.encode()+b'\0'; n=len(data)
        elif typ=='U': data=bytes(vals); n=len(data)
        elif typ=='B': data=bytes(vals); n=len(vals)
        elif typ=='H': data=struct.pack('<%dH'%len(vals),*vals); n=len(vals)
        elif typ=='I': data=struct.pack('<%dI'%len(vals),*vals); n=len(vals)
        elif typ in('R','SR'):
            fr=[]
            for v in vals: fr+= [int(round(v*10000)),10000]
            data=struct.pack(('<%di' if typ=='SR' else '<%dI')%len(fr),*fr); n=len(vals)
        ents.append((tag,T[typ],n,data))
    return ents
def write_ifd(ents,nxt=0):
    # dati lunghi prima
    fixed=[]
    for tag,typ,n,data in ents:
        if len(data)<=4: fixed.append((tag,typ,n,data.ljust(4,b'\0')))
        else: fixed.append((tag,typ,n,struct.pack('<I',add(data))))
    o=add(struct.pack('<H',len(fixed))+b''.join(struct.pack('<HHI',t,ty,n)+d for t,ty,n,d in fixed)+struct.pack('<I',nxt))
    return o
offs=[add(t) for t in tiles]
cm1=[0.9,-0.3,-0.05,-0.4,1.2,0.15,-0.05,0.2,0.6]
cm2=[0.8,-0.25,-0.05,-0.45,1.25,0.2,-0.1,0.25,0.65]
fm=[0.6,0.25,0.11,0.25,0.7,0.05,0.02,0.1,0.71]
raw_ifd=write_ifd(ifd([(254,'I',[0]),(256,'I',[W]),(257,'I',[H]),(258,'H',[16]),(259,'H',[7]),(262,'H',[32803]),(277,'H',[1]),
 (322,'I',[TW]),(323,'I',[TH]),(324,'I',offs),(325,'I',[len(t) for t in tiles]),(33421,'H',[2,2]),(33422,'B',pat),
 (50713,'H',[2,2]),(50714,'R',[black]*4),(50717,'I',[white]),(50719,'R',[0,0]),(50720,'R',[W,H]),(51009,'U',list(ops))]))
thumb=bytes(16*12*3)
toff=add(thumb)
ifd0=write_ifd(ifd([(254,'I',[1]),(256,'I',[16]),(257,'I',[12]),(258,'H',[8,8,8]),(259,'H',[1]),(262,'H',[2]),(273,'I',[toff]),(277,'H',[3]),(278,'I',[12]),(279,'I',[len(thumb)]),
 (271,'A','Google'),(272,'A','Pixel 8 Pro'),(274,'H',[6]),(305,'A','HDR+ 1.0 prova'),(330,'I',[raw_ifd]),
 (50706,'B',[1,4,0,0]),(50708,'A','Google-Pixel 8 Pro'),(50721,'SR',cm1),(50722,'SR',cm2),(50964,'SR',fm),(50965,'SR',fm),
 (50778,'H',[17]),(50779,'H',[21]),(50728,'R',list(neutro)),(50730,'SR',[0.0]),(51041,'R',[2e-5,1e-7]*3)]))
out[4:8]=struct.pack('<I',ifd0)
open('sintetico.dng','wb').write(out)
raw.tofile('sintetico.raw')
print('scritto',len(out),'byte')
