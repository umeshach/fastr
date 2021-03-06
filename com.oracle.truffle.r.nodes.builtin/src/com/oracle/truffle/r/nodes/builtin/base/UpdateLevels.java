/*
 * This material is distributed under the GNU General Public License
 * Version 2. You may review the terms of this license at
 * http://www.gnu.org/licenses/gpl-2.0.html
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates
 *
 * All rights reserved.
 */

package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.runtime.RDispatch.INTERNAL_GENERIC;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.PURE;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.r.nodes.builtin.CastBuilder;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RAttributeProfiles;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;

@RBuiltin(name = "levels<-", kind = PRIMITIVE, parameterNames = {"x", "value"}, dispatch = INTERNAL_GENERIC, behavior = PURE)
public abstract class UpdateLevels extends RBuiltinNode {

    @Override
    protected void createCasts(CastBuilder casts) {
        casts.arg("value").allowNull().asVector(false);
    }

    private final RAttributeProfiles attrProfiles = RAttributeProfiles.create();

    @Specialization
    protected RAbstractVector updateLevels(RAbstractVector vector, @SuppressWarnings("unused") RNull levels) {
        RVector<?> v = (RVector<?>) vector.getNonShared();
        v.removeAttr(attrProfiles, RRuntime.LEVELS_ATTR_KEY);
        return v;
    }

    @Specialization(guards = "!isRNull(levels)")
    protected RAbstractVector updateLevels(RAbstractVector vector, Object levels) {
        RVector<?> v = (RVector<?>) vector.getNonShared();
        v.setAttr(RRuntime.LEVELS_ATTR_KEY, levels);
        return v;
    }

    @Specialization
    protected RNull updateLevels(@SuppressWarnings("unused") RNull vector, @SuppressWarnings("unused") RNull levels) {
        return RNull.instance;
    }

    @Specialization(guards = "!isRNull(levels)")
    protected RAbstractVector updateLevels(@SuppressWarnings("unused") RNull vector, @SuppressWarnings("unused") Object levels) {
        throw RError.error(this, Message.SET_ATTRIBUTES_ON_NULL);
    }
}
