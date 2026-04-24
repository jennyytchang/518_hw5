package compress;

import java.util.*;

import dsl.*;
import ecg.Data;

public class Compress {

	public static final int BLOCK_SIZE = 10;
	public static final int BYTES_PACKED_PER_INT = 1; // Because apparently we aren't packing 4 bytes per int and each
	// int in the test is supposed to be a standin for an unsigned byte. I'm too lazy to rewrite the behaviour so I'm just
	// going to parameterize it and substitute it in as a parameter during shifting/packing operations
	// (since we can always just choose to only encode the LSB).
	// Revert this back to 4 for int packing behavior (or 2 for packing shorts I guess)
	public static final int BIT_WIDTH_HEADER_SIZE = 5; // Minimum value is 4, I originally tried to use this to see if can
	// compress further but it turned out to be an issue with the inclusion of EOS markers
	public static final boolean FLUSH_QUEUE_ON_BLOCK_COMPLETE = true; // Whether to force-flush the queue when a block is completed

	public static Query<Integer,Integer> delta() {
		// We need to store an anchor value for delta, so SWindow2 default implementation is not suitable (as it only
		// starts emitting when 2 values in window) so we need to implement our own.
		return new Query<Integer,Integer>() {
            private int prev;

            @Override
            public void start(Sink<Integer> sink) {
                this.prev = 0;
            }

            @Override
            public void next(Integer item, Sink<Integer> sink) {
                sink.next(item - prev);
                this.prev = item;
            }

            @Override
            public void end(Sink<Integer> sink) {
                sink.end();
            }
        };
	}

	public static Query<Integer,Integer> deltaInv() {
		return Q.scan(0, Integer::sum);
	}

	public static Query<Integer,Integer> zigzag() {
		// Zigzag is in essence XORing the multiplied by two version with the sign bit
		// which is the equivalent of 2x if x>0 (since sign bit is 0) or (-2x - 1) if x<0
		// Normally I wouldn't bother since this is a relatively simple operation but since we're talking embedded
		// devices probably better to use bitwise operations instead of an if/else loop.
		// This XOR behaviour leads to 2x for positive numbers (as x>>31 is 32 0s). XORing -1 against a negative number
		// results in -x -1, so this XOR behaviour results in -2x - 1
		return Q.map(x -> (x << 1) ^ (x >> 31)); // Since this is 32 bits

	}

	public static Query<Integer,Integer> zigzagInv() {
		// Basically reverse our encoding step with a logical instead of an arithmetic shift. We &1 to ensure that our
		// LSB is 0 from -1 and we do not affect the result
		return Q.map(x -> (x >>> 1) ^ -(x & 1));

	}

	public static Query<Integer,Integer> pack() {
		return new Query<Integer,Integer>() {
			private Deque<Integer> blockQueue = new LinkedList<>();
			private Deque<Byte> emitConstructionQueue = new LinkedList<>();
			private byte partiallyEncodedByte = 0;
			private int byteEncodedPosition = 0;

            @Override
            public void start(Sink<Integer> sink) {
				// nothing to do
            }

			private void appendBitsToPartiallyEncodedByte(Integer val, Integer width, Sink<Integer> sink) {
				int shift = 8 - width - byteEncodedPosition;
				int toInject = (val & ((1 << width) - 1)) << shift;
				// technically we should mask destination to be safe but its not rly necessary as we guarantee 0
				partiallyEncodedByte = (byte) (partiallyEncodedByte & 0xFF | toInject & 0xFF);
				byteEncodedPosition += width;
				if (byteEncodedPosition == 8) {
					emitConstructionQueue.addLast(partiallyEncodedByte);
					partiallyEncodedByte = 0;
					byteEncodedPosition = 0;
					if (emitConstructionQueue.size() == BYTES_PACKED_PER_INT) { // BYTES_PACKED_PER_INT bytes per Integer
						Integer out = 0;
						for (int i = 0; i < BYTES_PACKED_PER_INT; i++) {
							out |= (emitConstructionQueue.removeFirst() & 0xFF) << (i * 8);
						}
						sink.next(out);
					}
				}
			}

			// Appends <width> least significant bits of val into the byte represented
			// By partially encoded byte, handling emission to sink if necessary.
			private void appendBits(Integer val, Integer width, Sink<Integer> sink) {
				if (byteEncodedPosition + width > 8) {
					// Previous block did not leave us with enough space to pack in the full value so we have to
					// partially encode
					int numBitsRemaining =  8 - byteEncodedPosition;
					// move first numBitsRemaining of latter half of numBitsUsed into and then right shift to
					// place the first numBitsRemaining bits into the LSB positions
					int fragment = val  >>> (width - numBitsRemaining);
					appendBitsToPartiallyEncodedByte(fragment, numBitsRemaining, sink);
					numBitsRemaining = width - numBitsRemaining;
					int extracted = val & ((1 << numBitsRemaining) - 1); // Extract the last numBitsRemaining bits
					appendBitsToPartiallyEncodedByte(extracted, numBitsRemaining, sink);
				} else {
					appendBitsToPartiallyEncodedByte(val, width, sink);
				}
			}

            @Override
            public void next(Integer item, Sink<Integer> sink) {
				blockQueue.addLast(item);
				int base = 0;
				if (blockQueue.size() == BLOCK_SIZE) {
					// Determine maximum number of significant bits
					for (int i : blockQueue) {
						base |= i;
					}
					int numBitsUsed = (base == 0) ? 0 : 32 - Integer.numberOfLeadingZeros(base); // Does not need long since max value is 255 (8 bits/1 byte) which we only need 4 bits to represent
					appendBits(numBitsUsed, BIT_WIDTH_HEADER_SIZE, sink); // last BIT_WIDTH_HEADER_SIZE bits only
					if (numBitsUsed != 0) {
						// Unpacker just decodes BLOCK_SIZE 0s if it encounters all 0s in first four bits and assumes next BIT_WIDTH_HEADER_SIZE bits are header blocks denoting number
						// of bits used so we can just move on to the next
						while (!blockQueue.isEmpty()) {
							appendBits(blockQueue.removeFirst(), numBitsUsed, sink);
						}
					} else {
						blockQueue.clear(); // All 0s
					}
					// Flush bytes
					if (FLUSH_QUEUE_ON_BLOCK_COMPLETE) {
						if (byteEncodedPosition != 0) {
							emitConstructionQueue.addLast(partiallyEncodedByte);
							partiallyEncodedByte = 0;
							byteEncodedPosition = 0;
						}
					}
					while (!emitConstructionQueue.isEmpty()) {
						int out = 0;
						for (int i = 0; i < Math.min(BYTES_PACKED_PER_INT, emitConstructionQueue.size()); i++) {
							out |= (emitConstructionQueue.removeFirst() & 0xFF) << (i * 8);
						}
						sink.next(out);
					}
				}

            }

            @Override
            public void end(Sink<Integer> sink) {
				// Technically we should flush the incomplete block queue here but it is explicitly stated as not a requirement
				// (streams will always be a multiple of BLOCK_SIZE) and I am too lazy soo.... yeah
				// Clean up by emitting an EOS signal (we just use 0x0F as there are no values requiring 15 bits of 2-complement)
				if (byteEncodedPosition != 0) {
					appendBits(-1 >>> (32 - BIT_WIDTH_HEADER_SIZE), BIT_WIDTH_HEADER_SIZE, sink); // BIT_WIDTH_HEADER_SIZE 1s
				}
				if (byteEncodedPosition != 0) {
					emitConstructionQueue.addLast(partiallyEncodedByte);
				}
				// now clean up remaining emitConstructionQueue
				while (!emitConstructionQueue.isEmpty()) {
					Integer out = 0;
					for (int i = 0; i < (Math.min(BYTES_PACKED_PER_INT, emitConstructionQueue.size())); i++) {
						out |= (emitConstructionQueue.removeFirst() & 0xFF) << (i * 8);
					}
					sink.next(out);
				}
				// And wrap things up
				sink.end();
            }
        };

	}

	public static Query<Integer,Integer> unpack() {
		return new Query<Integer,Integer>() {
			private boolean eosSeen = false;

			private final Deque<Byte> byteQueue = new LinkedList<>();

			private int currentlyDecodedPos = 0;
			private int currBitWidth = -1;
			private int currBlockPos = 0;



            @Override
            public void start(Sink<Integer> sink) {
				// Nothing to do
            }

			private int readBits(int numBits) {
				int bitsAvailable = (8 - currentlyDecodedPos) + ((byteQueue.size() - 1) * 8);
				if (bitsAvailable < numBits) {
					return -1; // Not enough bits to decode
				} else {
					if (numBits + currentlyDecodedPos > 8) {
						// Need to split read, fully consuming the first item in the queue;
						int highBits = 8 - currentlyDecodedPos;
						int lowBits = numBits - highBits;
						currentlyDecodedPos = lowBits;
						int removed = byteQueue.removeFirst() & 0xFF;
						int next = byteQueue.peekFirst() & 0xFF;
						// We need to be careful of sign extension here as compressed values can have equivalent of negative
						// First filter only highBits, move to LSB, then shift left by lowBits to put in appropriate
						// position
						int high = ((removed & ((1 << highBits) - 1)) << lowBits);
						// For this we just need to mask for OR
						int low = (next >>> (8 - lowBits)) & ((1 << lowBits) - 1);
						// Should not happen but we're getting infinite looping here so throwing this condition just in case
						if (currentlyDecodedPos == 8) {
							byteQueue.removeFirst();
							currentlyDecodedPos = 0;
						}
						return (high | low) & ((1 << numBits) - 1);
                    } else {
						int shift = 8 - (numBits + currentlyDecodedPos);
						int mask = (1 << numBits) - 1;
						currentlyDecodedPos += numBits;
						int ret = ((byteQueue.peekFirst() & 0xFF)>>> shift) & mask;
						if (currentlyDecodedPos == 8) {
							byteQueue.removeFirst();
							currentlyDecodedPos = 0;
						}
						return ret;
					}
				}
			}

            @Override
            public void next(Integer item, Sink<Integer> sink) {
				if (eosSeen) {
					return; // Ignore trailing items if necessary, should not occur but you never know...
				}
				// Split int into BYTES_PACKED_PER_INT bytes
				for (int shift = 0; shift < BYTES_PACKED_PER_INT; shift += 1)
					byteQueue.addLast((byte) (item >>> (shift * 8)));

				emitFromByteQueue(sink);
            }

			private void emitFromByteQueue(Sink<Integer> sink) {
				// Perform the actual read inside a while loop to read as much as is possible in the
				// integers that we have
				while (true) {
					// - Need to read a bit width header
					if (currBitWidth == -1) {
						int bitWidth = readBits(BIT_WIDTH_HEADER_SIZE);
						if (bitWidth == -1) {
							return; // Not enough items in queue, move on to next item
						} else {
							if (bitWidth == (-1 >>> (32 - BIT_WIDTH_HEADER_SIZE))) {
								eosSeen = true;
								return;
							} else if (bitWidth == 0) {
								for (int i = 0; i < BLOCK_SIZE; i++) {
									sink.next(0);
								}
								// Reset padding since we are at end of block
								if (FLUSH_QUEUE_ON_BLOCK_COMPLETE) {
									if (currentlyDecodedPos != 0 && !byteQueue.isEmpty()) {
										byteQueue.removeFirst();
										currentlyDecodedPos = 0;
									}
								}
								continue;
							} else {
								currBitWidth = bitWidth;
							}
						}
					}
					// - Now read as many subsequent integers in the block as possible with the data we have
					while (currBlockPos < BLOCK_SIZE) {
						int next = readBits(currBitWidth);
						if (next == -1) {
							return;
						} else {
							sink.next(next);
						}
						currBlockPos++;
					}

					// - Completed the block, reset and repeat
					if (currBlockPos == BLOCK_SIZE) {
						if (FLUSH_QUEUE_ON_BLOCK_COMPLETE) {
							if (currentlyDecodedPos != 0 && !byteQueue.isEmpty()) {
								byteQueue.removeFirst();
								currentlyDecodedPos = 0;
							}
						}
						currBlockPos = 0;
						currBitWidth = -1;
					}
				}
			}

			@Override
            public void end(Sink<Integer> sink) {
				sink.end(); // We should not need to do anything here as we will naturally encounter EOS.
            }
        };
	}

	public static Query<Integer,Integer> compress() {
		return Q.pipeline(delta(), zigzag(), pack());
	}

	public static Query<Integer,Integer> decompress() {
		return Q.pipeline(unpack(), zigzagInv(), deltaInv());
	}

	public static void main(String[] args) {
		System.out.println("**********************************************");
		System.out.println("***** ToyDSL & Compression/Decompression *****");
		System.out.println("**********************************************");
		System.out.println();

		System.out.println("***** Compress *****");
		{
			// from range [0,2048) to [0,256)
			Query<Integer,Integer> q1 = Q.map(x -> x / 8);
			Query<Integer,Integer> q2 = compress();
			Query<Integer,Integer> q = Q.pipeline(q1, q2);
			Iterator<Integer> it = Data.ecgStream("100-all.csv");
			Q.execute(it, q, S.lastCount());
		}
		System.out.println();

		System.out.println("***** Compress & Decompress *****");
		{
			// from range [0,2048) to [0,256)
			Query<Integer,Integer> q1 = Q.map(x -> x / 8);
			Query<Integer,Integer> q2 = compress();
			Query<Integer,Integer> q3 = decompress();
			Query<Integer,Integer> q = Q.pipeline(q1, q2, q3);
			Iterator<Integer> it = Data.ecgStream("100-all.csv");
			Q.execute(it, q, S.lastCount());
		}
		System.out.println();
	}

}
