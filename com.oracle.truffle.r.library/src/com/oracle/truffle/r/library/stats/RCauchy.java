/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (C) 1998 Ross Ihaka
 * Copyright (c) 1998--2008, The R Core Team
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */
package com.oracle.truffle.r.library.stats;

import static com.oracle.truffle.r.library.stats.MathConstants.M_PI;

import com.oracle.truffle.r.library.stats.RandGenerationFunctions.RandFunction2_Double;
import com.oracle.truffle.r.library.stats.RandGenerationFunctions.RandomNumberProvider;

public final class RCauchy implements RandFunction2_Double {
    @Override
    public double evaluate(double location, double scale, RandomNumberProvider rand) {
        if (Double.isNaN(location) || !Double.isFinite(scale) || scale < 0) {
            return StatsUtil.mlError();
        }
        if (scale == 0. || !Double.isFinite(location)) {
            return location;
        } else {
            return location + scale * Math.tan(M_PI * rand.unifRand());
        }
    }
}
