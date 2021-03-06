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
package com.oracle.truffle.r.nodes.casts;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.oracle.truffle.r.nodes.builtin.TypePredicateArgumentFilter;
import com.oracle.truffle.r.nodes.casts.ArgumentFilterSampler.ArgumentTypeFilterSampler;

public class TypePredicateArgumentFilterSampler<T, R extends T> extends TypePredicateArgumentFilter<T, R> implements ArgumentTypeFilterSampler<T, R> {

    private final TypeExpr trueBranchTypes;
    private final Samples<R> samples;
    private final String desc;

    @SuppressWarnings("unchecked")
    public TypePredicateArgumentFilterSampler(String desc, Predicate<? super T> valuePredicate, Set<? extends R> positiveSamples, Set<?> negativeSamples, Set<Class<?>> allowedTypeSet) {
        super(valuePredicate);

        this.trueBranchTypes = allowedTypeSet.isEmpty() ? TypeExpr.ANYTHING : TypeExpr.union(allowedTypeSet);
        Predicate<Object> posMembership = x -> trueBranchTypes.isInstance(x) && test((T) x);
        this.samples = new Samples<>(desc, positiveSamples, negativeSamples, posMembership);

        assert positiveSamples.stream().allMatch(x -> valuePredicate.test(x));

        this.desc = desc;
    }

    @Override
    public TypeExpr trueBranchType() {
        return trueBranchTypes;
    }

    @Override
    public Samples<R> collectSamples(TypeExpr inputType) {
        return samples;
    }

    @Override
    public String toString() {
        return desc;
    }

    public static <T, R extends T> TypePredicateArgumentFilterSampler<T, R> fromLambda(Predicate<? super T> predicate, Set<? extends R> positiveSamples, Set<?> negativeSamples,
                    Class<?>... resultClass) {
        return new TypePredicateArgumentFilterSampler<>(CastUtils.getPredefStepDesc(), predicate, positiveSamples, negativeSamples, Arrays.asList(resultClass).stream().collect(Collectors.toSet()));
    }

    public static <T, R extends T> TypePredicateArgumentFilterSampler<T, R> fromLambda(Predicate<T> predicate, Class<?>... resultClass) {
        return new TypePredicateArgumentFilterSampler<>(CastUtils.getPredefStepDesc(), predicate, Collections.emptySet(), Collections.emptySet(),
                        Arrays.asList(resultClass).stream().collect(Collectors.toSet()));
    }

    public static <T, R extends T> TypePredicateArgumentFilterSampler<T, R> fromLambda(Predicate<T> predicate, Set<? extends R> positiveSamples, Set<?> negativeSamples) {
        return new TypePredicateArgumentFilterSampler<>(CastUtils.getPredefStepDesc(), predicate, positiveSamples, negativeSamples, Collections.emptySet());
    }

    public static <T, R extends T> TypePredicateArgumentFilterSampler<T, R> fromLambda(Predicate<T> predicate, @SuppressWarnings("unused") Class<R> commonAncestorClass, Set<Class<?>> resultClasses) {
        return new TypePredicateArgumentFilterSampler<>(CastUtils.getPredefStepDesc(), predicate, Collections.emptySet(), Collections.emptySet(), resultClasses);
    }
}
