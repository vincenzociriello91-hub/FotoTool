"""Codificatore JPEG senza perdita di riferimento: genera i casi di prova del decodificatore Kotlin."""
import numpy as np, struct, sys
def encode(a, P, pred, ri=0):
    # a: (Y, X, Nf) uint16
    Y,X,Nf=a.shape
    out=bytearray(b'\xff\xd8')
    # DHT: 17 simboli (0..16) tutti a 5 bit
    counts=[0]*16; counts[4]=17
    dht=bytes([0x00])+bytes(counts)+bytes(range(17))
    out+=b'\xff\xc4'+struct.pack('>H',len(dht)+2)+dht
    sof=bytes([P])+struct.pack('>HH',Y,X)+bytes([Nf])+b''.join(bytes([i+1,0x11,0]) for i in range(Nf))
    out+=b'\xff\xc3'+struct.pack('>H',len(sof)+2)+sof
    if ri: out+=b'\xff\xdd'+struct.pack('>HH',4,ri)
    sos=bytes([Nf])+b''.join(bytes([i+1,0x00]) for i in range(Nf))+bytes([pred,0,0])
    out+=b'\xff\xda'+struct.pack('>H',len(sos)+2)+sos
    bits=[]; data=bytearray(); rst=0
    def flush():
        nonlocal bits
        while len(bits)%8: bits.append(1)
        for i in range(0,len(bits),8):
            b=int(''.join(map(str,bits[i:i+8])),2); data.append(b)
            if b==0xff: data.append(0)
        bits=[]
    a=a.astype(np.int64); init=1<<(P-1); prima=0; mcu=0
    for y in range(Y):
        if ri and mcu and mcu%ri==0:
            flush(); data.extend(bytes([0xff,0xd0+(rst%8)])); rst+=1; prima=y
        for x in range(X):
            for k in range(Nf):
                if y==prima and x==0: p=init
                elif y==prima: p=a[y,x-1,k]
                elif x==0: p=a[y-1,x,k]
                else:
                    ra,rb,rc=a[y,x-1,k],a[y-1,x,k],a[y-1,x-1,k]
                    p={1:ra,2:rb,3:rc,4:ra+rb-rc,5:ra+((rb-rc)>>1),6:rb+((ra-rc)>>1),7:(ra+rb)>>1}[pred]
                diff=int((a[y,x,k]-p)) & 0xffff
                if diff>=32768: diff-=65536
                if diff==-32768: s=16
                else: s=0 if diff==0 else int(abs(diff)).bit_length()
                bits.extend(int(c) for c in format(s,'05b'))
                if s and s<16:
                    v=diff if diff>0 else diff+(1<<s)-1
                    bits.extend(int(c) for c in format(v,'0%db'%s))
            mcu+=1
    flush()
    out+=data+b'\xff\xd9'
    return bytes(out)
if __name__ == '__main__':
    rng=np.random.default_rng(2)
    n=2
    for (Y,X,Nf,P,pred,ri) in [(17,23,2,14,1,0),(16,20,2,16,4,0),(12,9,3,12,6,0),(10,11,1,10,7,0),(9,8,2,12,5,0),(8,6,2,16,2,0),(12,10,2,14,1,10),(6,7,1,8,3,0)]:
        base=np.cumsum(rng.integers(-40,40,(Y,X,Nf)),axis=1)+ (1<<(P-1))
        a=np.clip(base,0,(1<<P)-1).astype(np.uint16)
        e=encode(a,P,pred,ri)
        open(f'caso{n}.ljpg','wb').write(e); a.reshape(-1).astype('<u2').tofile(f'caso{n}.raw')
        print(n,(Y,X,Nf,P,pred,ri),len(e)); n+=1
