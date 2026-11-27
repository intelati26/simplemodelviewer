import subprocess, numpy as np, os, base64, sys
from concurrent.futures import ThreadPoolExecutor
W=(92,14,29,15)   # lon col0, lat row0, cols, rows: 230..300 E, 55..20 N
base="https://downloads.psl.noaa.gov/Datasets/ncep.reanalysis.dailyavgs/surface/pr_wtr.eatm.%d.nc"
ltm="https://downloads.psl.noaa.gov/Datasets/ncep.reanalysis.derived/surface/pr_wtr.eatm.day.ltm.1991-2020.nc"
def fetch(url,name):
    if not os.path.exists(name): subprocess.run(["curl","-s","-o",name,url],check=True)
def window(nc,out):
    subprocess.run(["gdal_translate","-q","-of","ENVI","-srcwin",*map(str,W),'NETCDF:"%s":pr_wtr'%nc,out],check=True)
    d=np.fromfile(out,dtype="<f4"); n=d.size//(W[2]*W[3]); return d.reshape(n,W[3],W[2])
fetch(ltm,"ltm.nc"); mean=window(os.path.abspath("ltm.nc"),"ltm.bin"); print("ltm",mean.shape)
def year(y):
    fetch(base%y,"y%d.nc"%y); return y,window(os.path.abspath("y%d.nc"%y),"y%d.bin"%y)
with ThreadPoolExecutor(6) as ex: years=dict(ex.map(year,range(1991,2021)))
days=92
sumsq=np.zeros((days,W[3],W[2])); cnt=np.zeros(days)
for y,a in years.items():
    leap=(y%4==0 and y%100!=0) or y%400==0
    for k in range(days):
        day=k*4+(1 if leap and k*4>=59 else 0)
        v=a[day]; m=mean[k*4]
        ok=np.isfinite(v)&(np.abs(v)<1e30)
        sumsq[k]+=np.where(ok,(v-m)**2,0); cnt[k]+=1
sig=np.zeros_like(sumsq)
for k in range(days):
    tot=sumsq[(k-1)%days]+sumsq[k]+sumsq[(k+1)%days]; n=cnt[(k-1)%days]+cnt[k]+cnt[(k+1)%days]
    sig[k]=np.sqrt(tot/n)
means=np.stack([mean[k*4] for k in range(days)])
print("mean range",means.min(),means.max(),"sigma range",sig.min(),sig.max(), "sigma median",np.median(sig))
mi=np.round(means*10).astype("<i2"); si=np.round(sig*10).astype("<i2")
np.save("means.npy",mi); np.save("sig.npy",si)
