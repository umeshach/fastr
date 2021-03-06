/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.runtime.data.model;

import com.oracle.truffle.r.runtime.data.RAttributable;
import com.oracle.truffle.r.runtime.data.RAttributeProfiles;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RShareable;
import com.oracle.truffle.r.runtime.data.RStringVector;
import com.oracle.truffle.r.runtime.data.RTypedValue;

public interface RAbstractContainer extends RAttributable, RTypedValue {

    boolean isComplete();

    int getLength();

    RAbstractContainer resize(int size);

    boolean hasDimensions();

    int[] getDimensions();

    void setDimensions(int[] newDimensions);

    Class<?> getElementClass();

    RTypedValue getNonShared();

    RShareable materializeToShareable();

    Object getDataAtAsObject(int index);

    /**
     * Note: elements inside lists may be in inconsistent state reference counting wise. You may
     * need to put them into consistent state depending on what you use them for, consult the
     * documentation of {@code ExtractListElement}.
     */
    default Object getDataAtAsObject(@SuppressWarnings("unused") Object store, int index) {
        return getDataAtAsObject(index);
    }

    default Object getInternalStore() {
        return null;
    }

    RStringVector getNames(RAttributeProfiles attrProfiles);

    void setNames(RStringVector newNames);

    RList getDimNames(RAttributeProfiles attrProfiles);

    void setDimNames(RList newDimNames);

    Object getRowNames(RAttributeProfiles attrProfiles);

    void setRowNames(RAbstractVector rowNames);
}
