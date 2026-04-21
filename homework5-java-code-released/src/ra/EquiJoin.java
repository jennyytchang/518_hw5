package ra;

import java.util.Objects;
import java.util.function.Function;

import dsl.Query;
import dsl.Sink;
import utils.Or;
import utils.Pair;

// A streaming implementation of the equi-join operator.
//
// We view the input as consisting of two channels:
// one with items of type A and one with items of type B.
// The output should contain all pairs (a, b) of input items,
// where a \in A is from the left channel, b \in B is from the
// right channel, and the equality predicate f(a) = g(b) holds.

public class EquiJoin<A,B,T> implements Query<Or<A,B>,Pair<A,B>> {

	private final ThetaJoin<A,B> impl;

	private EquiJoin(Function<A,T> f, Function<B,T> g) {
		this.impl = ThetaJoin.from((a, b) -> Objects.equals(f.apply(a), g.apply(b)));
	}

	public static <A,B,T> EquiJoin<A,B,T> from(Function<A,T> f, Function<B,T> g) {
		return new EquiJoin<>(f, g);
	}

	@Override
	public void start(Sink<Pair<A,B>> sink) {
		impl.start(sink);
	}

	@Override
	public void next(Or<A,B> item, Sink<Pair<A,B>> sink) {
		impl.next(item, sink);
	}

	@Override
	public void end(Sink<Pair<A,B>> sink) {
		impl.end(sink);
	}
}
