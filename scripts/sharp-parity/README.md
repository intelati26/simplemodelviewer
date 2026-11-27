# Sounding engine parity with wxqt-grib

`shared/src/jvmTest/kotlin/simplemodelview/SharpParityTest.kt` runs the Kotlin sounding engine (`Sharp*.kt`) and compares every number with the ones wxqt-grib's C++ engine
(`src/sounding`, itself checked against SPC's own values) gives for the same profiles. The expected numbers are in `shared/src/jvmTest/resources/sharp/wxqt_expected.txt`, made by
`harness.cpp` from wxqt-grib's sources:

```bash
SRC=~/Claude/wxqt-grib/src
g++ -O1 -std=c++17 -I$SRC harness.cpp $(ls $SRC/sounding/*.cpp | grep -v "Hazard\|Bufkit") -o harness
./harness ../../shared/src/jvmTest/resources/sharp/profiles.txt > ../../shared/src/jvmTest/resources/sharp/wxqt_expected.txt   # it prints the PROFILE ... ENDP blocks the test reads
```

`profiles.txt` holds model profiles (RRFS and GFS BUFKIT files): `PROFILE name latitude`, then `pressure height temperature dewpoint direction speed` per level, then `END`.
