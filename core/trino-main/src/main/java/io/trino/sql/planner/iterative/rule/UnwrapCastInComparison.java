/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner.iterative.rule;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import io.airlift.slice.Slice;
import io.trino.Session;
import io.trino.SystemSessionProperties;
import io.trino.metadata.Metadata;
import io.trino.metadata.OperatorNotFoundException;
import io.trino.metadata.ResolvedFunction;
import io.trino.spi.TrinoException;
import io.trino.spi.function.InvocationConvention;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.LongTimestampWithTimeZone;
import io.trino.spi.type.RealType;
import io.trino.spi.type.TimeWithTimeZoneType;
import io.trino.spi.type.TimeZoneKey;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.VarcharType;
import io.trino.sql.InterpretedFunctionInvoker;
import io.trino.sql.planner.ExpressionInterpreter;
import io.trino.sql.planner.LiteralEncoder;
import io.trino.sql.planner.NoOpSymbolResolver;
import io.trino.sql.planner.TypeAnalyzer;
import io.trino.sql.planner.TypeProvider;
import io.trino.sql.tree.Cast;
import io.trino.sql.tree.ComparisonExpression;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.ExpressionTreeRewriter;
import io.trino.sql.tree.IsNotNullPredicate;
import io.trino.sql.tree.IsNullPredicate;
import io.trino.sql.tree.NullLiteral;
import io.trino.type.TypeCoercion;

import java.lang.invoke.MethodHandle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.util.Optional;

import static com.google.common.base.Verify.verify;
import static io.airlift.slice.SliceUtf8.countCodePoints;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.trino.spi.type.DateTimeEncoding.unpackZoneKey;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static io.trino.spi.type.TypeUtils.isFloatingPointNaN;
import static io.trino.sql.ExpressionUtils.and;
import static io.trino.sql.ExpressionUtils.or;
import static io.trino.sql.analyzer.TypeSignatureTranslator.toSqlType;
import static io.trino.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.GREATER_THAN;
import static io.trino.sql.tree.ComparisonExpression.Operator.GREATER_THAN_OR_EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN;
import static io.trino.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static io.trino.sql.tree.ComparisonExpression.Operator.NOT_EQUAL;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Given s of type S, a constant expression t of type T, and when an implicit
 * cast exists between S->T, converts expression of the form:
 *
 * <pre>
 * CAST(s as T) = t
 * </pre>
 * <p>
 * into
 *
 * <pre>
 * s = CAST(t as S)
 * </pre>
 * <p>
 * For example:
 *
 * <pre>
 * CAST(x AS bigint) = bigint '1'
 * </pre>
 * <p>
 * turns into
 *
 * <pre>
 * x = smallint '1'
 * </pre>
 * <p>
 * It can simplify expressions that are known to be true or false, and
 * remove the comparisons altogether. For example, give x::smallint,
 * for an expression like:
 *
 * <pre>
 * CAST(x AS bigint) > bigint '10000000'
 * </pre>
 */
public class UnwrapCastInComparison
        extends ExpressionRewriteRuleSet
{
    public UnwrapCastInComparison(Metadata metadata, TypeOperators typeOperators, TypeAnalyzer typeAnalyzer)
    {
        super(createRewrite(metadata, typeOperators, typeAnalyzer));
    }

    private static ExpressionRewriter createRewrite(Metadata metadata, TypeOperators typeOperators, TypeAnalyzer typeAnalyzer)
    {
        requireNonNull(metadata, "metadata is null");
        requireNonNull(typeAnalyzer, "typeAnalyzer is null");

        return (expression, context) -> unwrapCasts(context.getSession(), metadata, typeOperators, typeAnalyzer, context.getSymbolAllocator().getTypes(), expression);
    }

    public static Expression unwrapCasts(Session session,
            Metadata metadata,
            TypeOperators typeOperators,
            TypeAnalyzer typeAnalyzer,
            TypeProvider types,
            Expression expression)
    {
        if (SystemSessionProperties.isUnwrapCasts(session)) {
            return ExpressionTreeRewriter.rewriteWith(new Visitor(metadata, typeOperators, typeAnalyzer, session, types), expression);
        }

        return expression;
    }

    private static class Visitor
            extends io.trino.sql.tree.ExpressionRewriter<Void>
    {
        private final Metadata metadata;
        private final TypeOperators typeOperators;
        private final TypeAnalyzer typeAnalyzer;
        private final Session session;
        private final TypeProvider types;
        private final InterpretedFunctionInvoker functionInvoker;
        private final LiteralEncoder literalEncoder;

        public Visitor(Metadata metadata, TypeOperators typeOperators, TypeAnalyzer typeAnalyzer, Session session, TypeProvider types)
        {
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.typeOperators = requireNonNull(typeOperators, "typeOperators is null");
            this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
            this.session = requireNonNull(session, "session is null");
            this.types = requireNonNull(types, "types is null");
            this.functionInvoker = new InterpretedFunctionInvoker(metadata);
            this.literalEncoder = new LiteralEncoder(metadata);
        }

        @Override
        public Expression rewriteComparisonExpression(ComparisonExpression node, Void context, ExpressionTreeRewriter<Void> treeRewriter)
        {
            ComparisonExpression expression = (ComparisonExpression) treeRewriter.defaultRewrite((Expression) node, null);
            return unwrapCast(expression);
        }

        private Expression unwrapCast(ComparisonExpression expression)
        {
            // Canonicalization is handled by CanonicalizeExpressionRewriter
            if (!(expression.getLeft() instanceof Cast)) {
                return expression;
            }

            Object right = new ExpressionInterpreter(expression.getRight(), metadata, session, typeAnalyzer.getTypes(session, types, expression.getRight()))
                    .optimize(NoOpSymbolResolver.INSTANCE);

            Cast cast = (Cast) expression.getLeft();
            ComparisonExpression.Operator operator = expression.getOperator();

            if (right == null || right instanceof NullLiteral) {
                switch (operator) {
                    case EQUAL:
                    case NOT_EQUAL:
                    case LESS_THAN:
                    case LESS_THAN_OR_EQUAL:
                    case GREATER_THAN:
                    case GREATER_THAN_OR_EQUAL:
                        return new Cast(new NullLiteral(), toSqlType(BOOLEAN));
                    case IS_DISTINCT_FROM:
                        return new IsNotNullPredicate(cast);
                }
                throw new UnsupportedOperationException("Not yet implemented");
            }

            if (right instanceof Expression) {
                return expression;
            }

            Type sourceType = typeAnalyzer.getType(session, types, cast.getExpression());
            Type targetType = typeAnalyzer.getType(session, types, expression.getRight());

            if (targetType instanceof TimestampWithTimeZoneType) {
                // Note: two TIMESTAMP WITH TIME ZONE values differing in zone only (same instant) are considered equal.
                right = withTimeZone(((TimestampWithTimeZoneType) targetType), right, session.getTimeZoneKey());
            }

            if (!hasInjectiveImplicitCoercion(sourceType, targetType, right)) {
                return expression;
            }

            // Handle comparison against NaN.
            // It must be done before source type range bounds are compared to target value.
            if (isFloatingPointNaN(targetType, right)) {
                switch (operator) {
                    case EQUAL:
                    case GREATER_THAN:
                    case GREATER_THAN_OR_EQUAL:
                    case LESS_THAN:
                    case LESS_THAN_OR_EQUAL:
                        return falseIfNotNull(cast.getExpression());
                    case NOT_EQUAL:
                        return trueIfNotNull(cast.getExpression());
                    case IS_DISTINCT_FROM:
                        if (!typeHasNaN(sourceType)) {
                            return TRUE_LITERAL;
                        }
                        else {
                            // NaN on the right of comparison will be cast to source type later
                            break;
                        }
                    default:
                        throw new UnsupportedOperationException("Not yet implemented: " + operator);
                }
            }

            ResolvedFunction sourceToTarget = metadata.getCoercion(sourceType, targetType);

            Optional<Type.Range> sourceRange = sourceType.getRange();
            if (sourceRange.isPresent()) {
                Object max = sourceRange.get().getMax();
                Object maxInTargetType = coerce(max, sourceToTarget);

                // NaN values of `right` are excluded at this point. Otherwise, NaN would be recognized as
                // greater than source type upper bound, and incorrect expression might be derived.
                int upperBoundComparison = compare(targetType, right, maxInTargetType);
                if (upperBoundComparison > 0) {
                    // larger than maximum representable value
                    switch (operator) {
                        case EQUAL:
                        case GREATER_THAN:
                        case GREATER_THAN_OR_EQUAL:
                            return falseIfNotNull(cast.getExpression());
                        case NOT_EQUAL:
                        case LESS_THAN:
                        case LESS_THAN_OR_EQUAL:
                            return trueIfNotNull(cast.getExpression());
                        case IS_DISTINCT_FROM:
                            return TRUE_LITERAL;
                    }
                    throw new UnsupportedOperationException("Not yet implemented: " + operator);
                }

                if (upperBoundComparison == 0) {
                    // equal to max representable value
                    switch (operator) {
                        case GREATER_THAN:
                            return falseIfNotNull(cast.getExpression());
                        case GREATER_THAN_OR_EQUAL:
                            return new ComparisonExpression(EQUAL, cast.getExpression(), literalEncoder.toExpression(max, sourceType));
                        case LESS_THAN_OR_EQUAL:
                            return trueIfNotNull(cast.getExpression());
                        case LESS_THAN:
                            return new ComparisonExpression(NOT_EQUAL, cast.getExpression(), literalEncoder.toExpression(max, sourceType));
                        case EQUAL:
                        case NOT_EQUAL:
                        case IS_DISTINCT_FROM:
                            return new ComparisonExpression(operator, cast.getExpression(), literalEncoder.toExpression(max, sourceType));
                    }
                    throw new UnsupportedOperationException("Not yet implemented: " + operator);
                }

                Object min = sourceRange.get().getMin();
                Object minInTargetType = coerce(min, sourceToTarget);

                int lowerBoundComparison = compare(targetType, right, minInTargetType);
                if (lowerBoundComparison < 0) {
                    // smaller than minimum representable value
                    switch (operator) {
                        case NOT_EQUAL:
                        case GREATER_THAN:
                        case GREATER_THAN_OR_EQUAL:
                            return trueIfNotNull(cast.getExpression());
                        case EQUAL:
                        case LESS_THAN:
                        case LESS_THAN_OR_EQUAL:
                            return falseIfNotNull(cast.getExpression());
                        case IS_DISTINCT_FROM:
                            return TRUE_LITERAL;
                    }
                    throw new UnsupportedOperationException("Not yet implemented: " + operator);
                }

                if (lowerBoundComparison == 0) {
                    // equal to min representable value
                    switch (operator) {
                        case LESS_THAN:
                            return falseIfNotNull(cast.getExpression());
                        case LESS_THAN_OR_EQUAL:
                            return new ComparisonExpression(EQUAL, cast.getExpression(), literalEncoder.toExpression(min, sourceType));
                        case GREATER_THAN_OR_EQUAL:
                            return trueIfNotNull(cast.getExpression());
                        case GREATER_THAN:
                            return new ComparisonExpression(NOT_EQUAL, cast.getExpression(), literalEncoder.toExpression(min, sourceType));
                        case EQUAL:
                        case NOT_EQUAL:
                        case IS_DISTINCT_FROM:
                            return new ComparisonExpression(operator, cast.getExpression(), literalEncoder.toExpression(min, sourceType));
                    }
                    throw new UnsupportedOperationException("Not yet implemented: " + operator);
                }
            }

            ResolvedFunction targetToSource;
            try {
                targetToSource = metadata.getCoercion(targetType, sourceType);
            }
            catch (OperatorNotFoundException e) {
                // Without a cast between target -> source, there's nothing more we can do
                return expression;
            }

            Object literalInSourceType;
            try {
                literalInSourceType = coerce(right, targetToSource);
            }
            catch (TrinoException e) {
                // A failure to cast from target -> source type could be because:
                //  1. missing cast
                //  2. bad implementation
                //  3. out of range or otherwise unrepresentable value
                // Since we can't distinguish between those cases, take the conservative option
                // and bail out.
                return expression;
            }

            Object roundtripLiteral = coerce(literalInSourceType, sourceToTarget);

            int literalVsRoundtripped = compare(targetType, right, roundtripLiteral);

            if (literalVsRoundtripped > 0) {
                // cast rounded down
                switch (operator) {
                    case EQUAL:
                        return falseIfNotNull(cast.getExpression());
                    case NOT_EQUAL:
                        return trueIfNotNull(cast.getExpression());
                    case IS_DISTINCT_FROM:
                        return TRUE_LITERAL;
                    case LESS_THAN:
                    case LESS_THAN_OR_EQUAL:
                        if (sourceRange.isPresent() && compare(sourceType, sourceRange.get().getMin(), literalInSourceType) == 0) {
                            return new ComparisonExpression(EQUAL, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                        }
                        return new ComparisonExpression(LESS_THAN_OR_EQUAL, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                    case GREATER_THAN:
                    case GREATER_THAN_OR_EQUAL:
                        // We expect implicit coercions to be order-preserving, so the result of converting back from target -> source cannot produce a value
                        // larger than the next value in the source type
                        return new ComparisonExpression(GREATER_THAN, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                }
                throw new UnsupportedOperationException("Not yet implemented: " + operator);
            }

            if (literalVsRoundtripped < 0) {
                // cast rounded up
                switch (operator) {
                    case EQUAL:
                        return falseIfNotNull(cast.getExpression());
                    case NOT_EQUAL:
                        return trueIfNotNull(cast.getExpression());
                    case IS_DISTINCT_FROM:
                        return TRUE_LITERAL;
                    case LESS_THAN:
                    case LESS_THAN_OR_EQUAL:
                        // We expect implicit coercions to be order-preserving, so the result of converting back from target -> source cannot produce a value
                        // smaller than the next value in the source type
                        return new ComparisonExpression(LESS_THAN, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                    case GREATER_THAN:
                    case GREATER_THAN_OR_EQUAL:
                        if (sourceRange.isPresent() && compare(sourceType, sourceRange.get().getMax(), literalInSourceType) == 0) {
                            return new ComparisonExpression(EQUAL, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                        }
                        return new ComparisonExpression(GREATER_THAN_OR_EQUAL, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
                }
                throw new UnsupportedOperationException("Not yet implemented: " + operator);
            }

            return new ComparisonExpression(operator, cast.getExpression(), literalEncoder.toExpression(literalInSourceType, sourceType));
        }

        private boolean hasInjectiveImplicitCoercion(Type source, Type target, Object value)
        {
            if ((source.equals(BIGINT) && target.equals(DOUBLE)) ||
                    (source.equals(BIGINT) && target.equals(REAL)) ||
                    (source.equals(INTEGER) && target.equals(REAL))) {
                // Not every BIGINT fits in DOUBLE/REAL due to 64 bit vs 53-bit/23-bit mantissa. Similarly,
                // not every INTEGER fits in a REAL (32-bit vs 23-bit mantissa)
                if (target.equals(DOUBLE)) {
                    double doubleValue = (double) value;
                    return doubleValue > Long.MAX_VALUE ||
                            doubleValue < Long.MIN_VALUE ||
                            Double.isNaN(doubleValue) ||
                            (doubleValue > -1L << 53 && doubleValue < 1L << 53); // in (-2^53, 2^53), bigint follows an injective implicit coercion w.r.t double
                }
                else {
                    float realValue = intBitsToFloat(toIntExact((long) value));
                    return (source.equals(BIGINT) && (realValue > Long.MAX_VALUE || realValue < Long.MIN_VALUE)) ||
                            (source.equals(INTEGER) && (realValue > Integer.MAX_VALUE || realValue < Integer.MIN_VALUE)) ||
                            Float.isNaN(realValue) ||
                            (realValue > -1L << 23 && realValue < 1L << 23); // in (-2^23, 2^23), bigint (and integer) follows an injective implicit coercion w.r.t real
                }
            }

            if (source instanceof DecimalType) {
                int precision = ((DecimalType) source).getPrecision();

                if (precision > 15 && target.equals(DOUBLE)) {
                    // decimal(p,s) with p > 15 doesn't fit in a double without loss
                    return false;
                }

                if (precision > 7 && target.equals(REAL)) {
                    // decimal(p,s) with p > 7 doesn't fit in a double without loss
                    return false;
                }
            }

            if (target instanceof TimestampWithTimeZoneType) {
                TimestampWithTimeZoneType timestampWithTimeZoneType = (TimestampWithTimeZoneType) target;
                if (source instanceof TimestampType) {
                    // Cast from TIMESTAMP WITH TIME ZONE to TIMESTAMP and back to TIMESTAMP WITH TIME ZONE does not round trip, unless the value's zone is equal to sesion zone
                    if (!getTimeZone(timestampWithTimeZoneType, value).equals(session.getTimeZoneKey())) {
                        return false;
                    }

                    // Cast from TIMESTAMP to TIMESTAMP WITH TIME ZONE is not monotonic when there is a forward DST change in the session zone
                    if (!isTimestampToTimestampWithTimeZoneInjectiveAt(session.getTimeZoneKey().getZoneId(), getInstantWithTruncation(timestampWithTimeZoneType, value))) {
                        return false;
                    }

                    return true;
                }
                // CAST from TIMESTAMP WITH TIME ZONE to d and back to TIMESTAMP WITH TIME ZONE does not round trip for most types d
                // TODO add test coverage
                // TODO (https://github.com/trinodb/trino/issues/5798) handle DATE -> TIMESTAMP WITH TIME ZONE
                return false;
            }

            if (target instanceof TimeWithTimeZoneType) {
                // For example, CAST from TIME WITH TIME ZONE to TIME and back to TIME WITH TIME ZONE does not round trip

                // TODO add test coverage
                return false;
            }

            boolean coercible = new TypeCoercion(metadata::getType).canCoerce(source, target);
            if (source instanceof VarcharType && target instanceof CharType) {
                // char should probably be coercible to varchar, not vice-versa. The code here needs to be updated when things change.
                verify(coercible, "%s was expected to be coercible to %s", source, target);

                VarcharType sourceVarchar = (VarcharType) source;
                CharType targetChar = (CharType) target;

                if (sourceVarchar.isUnbounded() || sourceVarchar.getBoundedLength() > targetChar.getLength()) {
                    // Truncation, not injective.
                    return false;
                }
                if (sourceVarchar.getBoundedLength() == 0) {
                    // the source domain is single-element set
                    return true;
                }
                int actualLengthWithoutSpaces = countCodePoints((Slice) value);
                verify(actualLengthWithoutSpaces <= targetChar.getLength(), "Incorrect char value [%s] for %s", ((Slice) value).toStringUtf8(), targetChar);
                return sourceVarchar.getBoundedLength() == actualLengthWithoutSpaces;
            }

            // Well-behaved implicit casts are injective
            return coercible;
        }

        private Object coerce(Object value, ResolvedFunction coercion)
        {
            return functionInvoker.invoke(coercion, session.toConnectorSession(), value);
        }

        private boolean typeHasNaN(Type type)
        {
            return type instanceof DoubleType || type instanceof RealType;
        }

        private int compare(Type type, Object first, Object second)
        {
            requireNonNull(first, "first is null");
            requireNonNull(second, "second is null");
            MethodHandle comparisonOperator = typeOperators.getComparisonOperator(type, InvocationConvention.simpleConvention(FAIL_ON_NULL, NEVER_NULL, NEVER_NULL));
            try {
                return (int) (long) comparisonOperator.invoke(first, second);
            }
            catch (Throwable throwable) {
                Throwables.throwIfUnchecked(throwable);
                throw new TrinoException(GENERIC_INTERNAL_ERROR, throwable);
            }
        }
    }

    /**
     * Replace time zone component of a {@link TimestampWithTimeZoneType} value with a given one, preserving point in time
     * (equivalent to {@link java.time.ZonedDateTime#withZoneSameInstant}.
     */
    private static Object withTimeZone(TimestampWithTimeZoneType type, Object value, TimeZoneKey newZone)
    {
        if (type.isShort()) {
            return packDateTimeWithZone(unpackMillisUtc((long) value), newZone);
        }
        LongTimestampWithTimeZone longTimestampWithTimeZone = (LongTimestampWithTimeZone) value;
        return LongTimestampWithTimeZone.fromEpochMillisAndFraction(longTimestampWithTimeZone.getEpochMillis(), longTimestampWithTimeZone.getPicosOfMilli(), newZone);
    }

    private static TimeZoneKey getTimeZone(TimestampWithTimeZoneType type, Object value)
    {
        if (type.isShort()) {
            return unpackZoneKey(((long) value));
        }
        return TimeZoneKey.getTimeZoneKey(((LongTimestampWithTimeZone) value).getTimeZoneKey());
    }

    @VisibleForTesting
    static boolean isTimestampToTimestampWithTimeZoneInjectiveAt(ZoneId zone, Instant instant)
    {
        ZoneOffsetTransition transition = zone.getRules().previousTransition(instant.plusNanos(1));
        if (transition != null) {
            // DST change forward and the instant is ambiguous, being within the 'gap' area non-monotonic remapping
            if (!transition.getDuration().isNegative() && !transition.getDateTimeAfter().minusNanos(1).atZone(zone).toInstant().isBefore(instant)) {
                return false;
            }
        }
        return true;
    }

    private static Instant getInstantWithTruncation(TimestampWithTimeZoneType type, Object value)
    {
        if (type.isShort()) {
            return Instant.ofEpochMilli(unpackMillisUtc(((long) value)));
        }
        LongTimestampWithTimeZone longTimestampWithTimeZone = (LongTimestampWithTimeZone) value;
        return Instant.ofEpochMilli(longTimestampWithTimeZone.getEpochMillis())
                .plus(longTimestampWithTimeZone.getPicosOfMilli() / PICOSECONDS_PER_NANOSECOND, ChronoUnit.NANOS);
    }

    private static Expression falseIfNotNull(Expression argument)
    {
        return and(new IsNullPredicate(argument), new NullLiteral());
    }

    private static Expression trueIfNotNull(Expression argument)
    {
        return or(new IsNotNullPredicate(argument), new NullLiteral());
    }
}
