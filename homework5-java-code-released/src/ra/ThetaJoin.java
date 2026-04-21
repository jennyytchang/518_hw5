package ra;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import dsl.Query;
import dsl.Sink;
import utils.Or;
import utils.Pair;

// A streaming implementation of the theta join operator.
//
// We view the input as consisting of two channels:
// one with items of type A and one with items of type B.
// The output should contain all pairs (a, b) of input items,
// where a \in A is from the left channel, b \in B is from the
// right channel, and the pair (a, b) satisfies a predicate theta.

public class ThetaJoin<A,B> implements Query<Or<A,B>,Pair<A,B>> {

	private final BiPredicate<A,B> theta;
	private final List<A> lefts;
	private final List<B> rights;

	private ThetaJoin(BiPredicate<A,B> theta) {
		this.theta = theta;
		this.lefts = new ArrayList<>();
		this.rights = new ArrayList<>();
	}

	public static <A,B> ThetaJoin<A,B> from(BiPredicate<A,B> theta) {
		return new ThetaJoin<>(theta);
	}

	@Override
	public void start(Sink<Pair<A,B>> sink) {
		lefts.clear();
		rights.clear();
	}

	@Override
	public void next(Or<A,B> item, Sink<Pair<A,B>> sink) {
		if (item.isLeft()) {
			A a = item.getLeft();
			for (B b : rights) {
				if (theta.test(a, b)) {
					sink.next(Pair.from(a, b));
				}
			}
			lefts.add(a);
		} else {
			B b = item.getRight();
			for (A a : lefts) {
				if (theta.test(a, b)) {
					sink.next(Pair.from(a, b));
				}
			}
			rights.add(b);
		}
	}

	@Override
	public void end(Sink<Pair<A,B>> sink) {
		sink.end();
	}
	
}
