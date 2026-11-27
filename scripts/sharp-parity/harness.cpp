#include <cstdio>
#include <cmath>
#include <fstream>
#include <sstream>
#include <string>
#include "sounding/SoundingAnalysis.h"
#include "sounding/SoundingPrecip.h"
using namespace std;
static void pr(const char* k, double v) { printf("%s %.6f\n", k, v); }
static void parcel(const char* n, const SoundingParcel::Parcel& p) {
    char b[64];
    auto w = [&](const char* f, double v) { snprintf(b, 64, "%s.%s", n, f); pr(b, v); };
    w("cape", p.cape); w("cin", p.cin); w("lcl", p.lclHght); w("lfc", p.lfcHght); w("el", p.elHght); w("mpl", p.mplHght); w("cape3", p.cape3km); w("li5", p.liftedIndex500); w("li3", p.liftedIndex300); w("lclp", p.lclPres);
}
static void wind(const char* n, const SoundingIndices::Wind& x) { char b[64]; snprintf(b,64,"%s.u",n); pr(b,x.u); snprintf(b,64,"%s.v",n); pr(b,x.v); }
static void lk(const char* n, const SoundingIndices::LayerKinematics& k) { char b[64]; snprintf(b,64,"%s.shear",n); wind(b,k.shear); snprintf(b,64,"%s.mean",n); wind(b,k.mean); snprintf(b,64,"%s.sr",n); wind(b,k.stormRelative); }
int main(int argc, char** argv) {
    ifstream in(argv[1]); string line;
    while (getline(in, line)) {
        if (line.rfind("PROFILE", 0) != 0) continue;
        istringstream h(line); string tag, name; double lat; h >> tag >> name >> lat;
        SoundingProfile p; p.latitude = lat;
        while (getline(in, line) && line != "END") { istringstream s(line); double a,b,c,d,e,f; s >> a >> b >> c >> d >> e >> f; p.pres.push_back(a); p.hght.push_back(b); p.tmpc.push_back(c); p.dwpc.push_back(d); p.wdir.push_back(e); p.wspd.push_back(f); }
        p.finalize();
        printf("PROFILE %s\n", name.c_str());
        auto a = SoundingAnalysis::compute(p);
        parcel("sb", a.sb); parcel("ml", a.ml); parcel("mu", a.mu); parcel("fcst", a.fcst); pr("maxT", a.maxTempC);
        pr("eff.valid", a.effective.valid); pr("eff.bot", a.effective.botAgl); pr("eff.top", a.effective.topAgl); pr("eff.pbot", a.effective.pBot); pr("eff.ptop", a.effective.pTop);
        wind("rm", a.rightMover); wind("lm", a.leftMover); wind("mw06", a.meanWind06);
        pr("srh01", a.srh01); pr("srh03", a.srh03); pr("esrh", a.effectiveSrh); pr("eshear", a.effectiveShearKt);
        wind("corf.up", a.corfidi.upshear); wind("corf.dn", a.corfidi.downshear); pr("crit", a.criticalAngle);
        lk("k1", a.kin1); lk("k3", a.kin3); lk("keff", a.kinEff); lk("k6", a.kin6); lk("k8", a.kin8); lk("kcloud", a.kinCloud); lk("kebwd", a.kinEbwd); lk("k46", a.kin46);
        pr("brn", a.brnShear); pr("downT", a.downTempC); pr("dcape", a.dcape); pr("pw", a.precipitableWaterIn); pr("mmix100", a.meanMixingLow100); pr("mmix03", a.meanMixing03);
        pr("lapse03", a.lapse03); pr("lapse36", a.lapse36); pr("lapse75", a.lapse700500); pr("lapse85", a.lapse850500);
        pr("convT", a.convectiveTemp); pr("frz", a.freezingLevelAgl); pr("wbz", a.wetBulbZeroAgl); pr("sfcrh", a.surfaceRh);
        pr("k", a.kIndex); pr("tt", a.totalTotals); pr("lowrh", a.lowRh); pr("midrh", a.midRh); pr("esp", a.esp); pr("wndg", a.wndg); pr("sigsvr", a.sigSevere); pr("mmp", a.mmp);
        pr("stpf", a.stpFixed); pr("stpe", a.stpEffective); pr("scp", a.supercell); pr("ship", a.hail);
        pr("sars.sc.loose", a.sarsSupercell.looseMatches); pr("sars.sc.sig", a.sarsSupercell.significant); pr("sars.sc.prob", a.sarsSupercell.probability); pr("sars.sc.q", a.sarsSupercell.quality.size());
        pr("sars.h.loose", a.sarsHail.looseMatches); pr("sars.h.sig", a.sarsHail.significant); pr("sars.h.prob", a.sarsHail.probability); pr("sars.h.q", a.sarsHail.quality.size());
        pr("tei", SoundingIndices::thetaEIndex(p));
        auto g = SoundingPrecip::bestGuess(p); printf("precip %s\n", g.type.c_str());
        printf("ENDP\n");
    }
}
