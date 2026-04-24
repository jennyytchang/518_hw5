package ecg;

import java.util.ArrayList;
import java.util.List;

import dsl.S;
import dsl.Q;
import dsl.Query;

public class PeakDetection {

	// The curve length transformation:
	//
	// adjust: x[n] = raw[n] - 1024
	// smooth: y[n] = (x[n-2] + x[n-1] + x[n] + x[n+1] + x[n+2]) / 5
	// deriv: d[n] = (y[n+1] - y[n-1]) / 2
	// length: l[n] = t(d[n-w]) + ... + t(d[n+w]), where
	//         w = 20 (samples) and t(d) = sqrt(1.0 + d * d)

	public static Query<Integer,Double> qLength() {
		return new LengthQuery();
	}

	// In order to detect peaks we need both the raw (or adjusted)
	// signal and the signal given by the curve length transformation.
	// Use the datatype VTL and implement the class Detect.

	public static Query<Integer,Long> qPeaks() {
		return Q.pipeline(new RawToVTL(), new Detect());
	}

	private static double[] computeLength(List<Integer> raw) {
		int n = raw.size();
		if (n < 25) {
			return new double[0];
		}

		double[] x = new double[n];
		for (int i = 0; i < n; i++) {
			x[i] = raw.get(i) - 1024.0;
		}

		double[] y = new double[n];
		for (int i = 2; i <= n - 3; i++) {
			y[i] = (x[i - 2] + x[i - 1] + x[i] + x[i + 1] + x[i + 2]) / 5.0;
		}

		double[] d = new double[n];
		for (int i = 3; i <= n - 4; i++) {
			d[i] = (y[i + 1] - y[i - 1]) / 2.0;
		}

		int w = 20;
		double[] l = new double[n];
		for (int i = 3 + w; i <= n - 5 - w; i++) {
			double sum = 0.0;
			for (int j = i - w; j <= i + w; j++) {
				sum += Math.sqrt(1.0 + d[j] * d[j]);
			}
			l[i] = sum;
		}

		return l;
	}

	private static class LengthQuery implements Query<Integer,Double> {
		private final List<Integer> raw = new ArrayList<>();

		@Override
		public void start(dsl.Sink<Double> sink) {
			raw.clear();
		}

		@Override
		public void next(Integer item, dsl.Sink<Double> sink) {
			raw.add(item);
		}

		@Override
		public void end(dsl.Sink<Double> sink) {
			double[] l = computeLength(raw);
			for (int i = 23; i <= raw.size() - 25; i++) {
				sink.next(l[i]);
			}
			sink.end();
		}
	}

	private static class RawToVTL implements Query<Integer,VTL> {
		private final List<Integer> raw = new ArrayList<>();

		@Override
		public void start(dsl.Sink<VTL> sink) {
			raw.clear();
		}

		@Override
		public void next(Integer item, dsl.Sink<VTL> sink) {
			raw.add(item);
		}

		@Override
		public void end(dsl.Sink<VTL> sink) {
			double[] l = computeLength(raw);
			for (int i = 23; i <= raw.size() - 25; i++) {
				sink.next(new VTL(raw.get(i), i, l[i]));
			}
			sink.end();
		}
	}

	public static void main(String[] args) {
		System.out.println("****************************************");
		System.out.println("***** Algorithm for Peak Detection *****");
		System.out.println("****************************************");
		System.out.println();

		Q.execute(Data.ecgStream("100.csv"), qPeaks(), S.printer());
	}

}
