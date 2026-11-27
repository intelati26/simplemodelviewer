# SHARPpy attribution

The sounding calculations of this app (`shared/src/commonMain/kotlin/simplemodelview/SoundingMath.kt`, `SoundingSharp.kt` and `SoundingHazard.kt`) reimplement algorithms
from **SHARPpy** (https://github.com/sharppy/SHARPpy), the open-source port of the Storm Prediction Center's SHARP/NSHARP sounding software, by way of the checked ports in
wxqt-grib (https://github.com/intelati26/wxqt-grib, GPLv3: `src/sounding/`). They follow the structure and formulas of SHARPpy's `sharptab/params.py` (`parcelx`,
`effective_inflow_layer`, `bunkers_storm_motion`, `dcape`, `stp_fixed`, `stp_cin`, `scp`, `ship`, the WNDG and MMP indices, `inferred_temp_adv` is not ported),
`sharptab/winds.py` (`helicity`, `mean_wind`, `wind_shear`, `non_parcel_bunkers_motion`) and `sharptab/watch_type.py` (`possible_watch`, the possible hazard type). Where
wxqt-grib found SPC's printed values to differ from SHARPpy's code, the version here follows wxqt-grib (and so SPC): see the comments in `SoundingSharp.kt`.

SHARPpy is distributed under the BSD 3-clause license:

    Copyright (c) 2011, Patrick T. Marsh & John Hart.
    Copyright (c) 2012, MetPy Developers.
    Copyright (c) 2020, Kelton Halbert, Greg Blumberg & Tim Supinie.
    All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice,
      this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.
    * Neither the name of the MetPy Developers nor the names of any contributors
      may be used to endorse or promote products derived from this software
      without specific permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
    ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
    LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
    CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
    SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
    INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
    CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
    ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
    POSSIBILITY OF SUCH DAMAGE.
