/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.nodes.unary;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.builtin.ArgumentFilter;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;

public abstract class ConditionalMapNode extends CastNode {

    private final ArgumentFilter<?, ?> argFilter;
    private final ConditionProfile conditionProfile = ConditionProfile.createBinaryProfile();

    @Child private CastNode trueBranch;
    @Child private CastNode falseBranch;

    protected ConditionalMapNode(ArgumentFilter<?, ?> argFilter, CastNode trueBranch, CastNode falseBranch) {
        this.argFilter = argFilter;
        this.trueBranch = trueBranch;
        this.falseBranch = falseBranch;
    }

    public static ConditionalMapNode create(ArgumentFilter<?, ?> argFilter, CastNode trueBranch,
                    CastNode falseBranch) {
        return ConditionalMapNodeGen.create(argFilter, trueBranch, falseBranch);
    }

    public ArgumentFilter<?, ?> getFilter() {
        return argFilter;
    }

    public CastNode getTrueBranch() {
        return trueBranch;
    }

    public CastNode getFalseBranch() {
        return falseBranch;
    }

    @Specialization
    protected RNull executeNull(@SuppressWarnings("unused") RNull x) {
        return RNull.instance;
    }

    @Specialization
    protected RMissing executeMissing(@SuppressWarnings("unused") RMissing x) {
        return RMissing.instance;
    }

    @SuppressWarnings("unchecked")
    @Specialization
    protected Object executeRest(Object x) {
        if (conditionProfile.profile(((ArgumentFilter<Object, Object>) argFilter).test(x))) {
            return trueBranch == null ? x : trueBranch.execute(x);
        } else {
            return falseBranch == null ? x : falseBranch.execute(x);
        }
    }
}
