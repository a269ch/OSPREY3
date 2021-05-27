package edu.duke.cs.osprey.gpu;

import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemoryHandles;
import kotlin.text.Charsets;

import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;


/**
 * A crude way to represent and access C structs and arrays
 * using MemoryAddresses and MemoryHandles from the Foreign-Memory Access API.
 */
public class Structs {

	public static abstract class Struct {

		private Field[] fields = null;

		public <T extends Struct> T init(int bytes, String ... fieldNames) {

			// get the fields
			Class<?> c = getClass();
			fields = new Field[fieldNames.length];
			for (int i=0; i<fieldNames.length; i++) {
				String fieldName = fieldNames[i];
				try {
					var declaredField = c.getDeclaredField(fieldName);
					declaredField.setAccessible(true);
					fields[i] = (Field)declaredField.get(this);
					if (fields[i] == null) {
						throw new Error("Field hasn't been assigned yet: " + fieldName);
					}
					fields[i].name = fieldName;
				} catch (NoSuchFieldException ex) {
					throw new Error("Can't initialize field: " + fieldName, ex);
				} catch (IllegalAccessException ex) {
					throw new Error("Can't read field: " + fieldName, ex);
				}
			}

			// find any missing fields
			Set<String> missingFields = Arrays.stream(c.getDeclaredFields())
				.filter(f -> !f.isSynthetic()) // ignore fields generated by the compiler
				.map(f -> f.getName())
				.collect(Collectors.toSet());
			for (String name : fieldNames) {
				missingFields.remove(name);
			}
			if (!missingFields.isEmpty()) {
				throw new IllegalArgumentException("no order given for fields: " + missingFields);
			}

			// check the field sizes
			if (bytes != bytes()) {
				throw new IllegalArgumentException("struct size (" + bytes() + ") is not expected size (" + bytes + ")");
			}

			// set the field offsets
			long offset = 0;
			for (var field : fields) {
				field.offset = offset;
				offset += field.bytes;
			}

			@SuppressWarnings("unchecked")
			T struct = (T)this;
			return struct;
		}

		/**
		 * Calculates the static size of the struct.
		 */
		public long bytes() {
			return sum(fields, f -> f.bytes);
		}
	}

	public static <T> long sum(T[] things, ToLongFunction<? super T> converter) {
		return Arrays.stream(things)
			.mapToLong(converter)
			.sum();
	}

	public static abstract class Field {

		public final long bytes;

		protected String name;
		protected long offset;

		public Field(long bytes) {
			this.bytes = bytes;
		}

		public String name() {
			return name;
		}

		public long offset() {
			return offset;
		}
	}

	public static abstract class Array {

		public final long itemBytes;

		public Array(long itemBytes) {
			this.itemBytes = itemBytes;
		}

		public long bytes(long size) {
			return size*itemBytes;
		}
	}

	public static class Pad extends Field {

		public Pad(long bytes) {
			super(bytes);
		}
	}
	public static Pad pad(long bytes) {
		return new Pad(bytes);
	}

	public static class Int32 extends Field {

		public static final long bytes = 4;
		private static final VarHandle handle = MemoryHandles.varHandle(int.class, ByteOrder.nativeOrder());

		public Int32() {
			super(bytes);
		}

		public int get(MemoryAddress addr) {
			return (int)handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, int value) {
			handle.set(addr.addOffset(offset), value);
		}

		public static class Array extends Structs.Array {

			public Array() {
				super(bytes);
			}

			public int get(MemoryAddress addr, long i) {
				return (int)handle.get(addr.addOffset(i*bytes));
			}

			public void set(MemoryAddress addr, long i, int value) {
				handle.set(addr.addOffset(i*bytes), value);
			}
		}
	}
	public static Int32 int32() {
		return new Int32();
	}
	public static Int32.Array int32array() {
		return new Int32.Array();
	}

	public static class Int64 extends Field {

		public static final long bytes = 8;
		private static final VarHandle handle = MemoryHandles.varHandle(long.class, ByteOrder.nativeOrder());

		public Int64() {
			super(bytes);
		}

		public long get(MemoryAddress addr) {
			return (long)handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, long value) {
			handle.set(addr.addOffset(offset), value);
		}

		public static class Array extends Structs.Array {

			public Array() {
				super(bytes);
			}

			public long get(MemoryAddress addr, long i) {
				return (long)handle.get(addr.addOffset(i*bytes));
			}

			public void set(MemoryAddress addr, long i, long value) {
				handle.set(addr.addOffset(i*bytes), value);
			}
		}
	}
	public static Int64 int64() {
		return new Int64();
	}
	public static Int64.Array int64array() {
		return new Int64.Array();
	}


	public static class Float32 extends Field {

		public static final long bytes = 4;
		private static final VarHandle handle = MemoryHandles.varHandle(float.class, ByteOrder.nativeOrder());

		public Float32() {
			super(bytes);
		}

		public float get(MemoryAddress addr) {
			return (float)handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, float value) {
			handle.set(addr.addOffset(offset), value);
		}

		public static class Array extends Structs.Array {

			public Array() {
				super(bytes);
			}

			public float get(MemoryAddress addr, long i) {
				return (float)handle.get(addr.addOffset(i*bytes));
			}

			public void set(MemoryAddress addr, long i, float value) {
				handle.set(addr.addOffset(i*bytes), value);
			}
		}
	}
	public static Float32 float32() {
		return new Float32();
	}
	public static Float32.Array float32array() {
		return new Float32.Array();
	}

	public static class Float64 extends Field {

		public static final long bytes = 8;
		private static final VarHandle handle = MemoryHandles.varHandle(double.class, ByteOrder.nativeOrder());

		public Float64() {
			super(bytes);
		}

		public double get(MemoryAddress addr) {
			return (double)handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, double value) {
			handle.set(addr.addOffset(offset), value);
		}

		public static class Array extends Structs.Array {

			public Array() {
				super(bytes);
			}

			public double get(MemoryAddress addr, long i) {
				return (double)handle.get(addr.addOffset(i*bytes));
			}

			public void set(MemoryAddress addr, long i, double value) {
				handle.set(addr.addOffset(i*bytes), value);
			}
		}
	}
	public static Float64 float64() {
		return new Float64();
	}
	public static Float64.Array float64array() {
		return new Float64.Array();
	}

	public static class Bool extends Field {

		public static final long bytes = 1;
		private static final VarHandle handle = MemoryHandles.varHandle(byte.class, ByteOrder.nativeOrder());

		public Bool() {
			super(bytes);
		}

		public boolean get(MemoryAddress addr) {
			return (byte)handle.get(addr.addOffset(offset)) != 0;
		}

		public void set(MemoryAddress addr, boolean value) {
			handle.set(addr.addOffset(offset), value ? (byte)1 : (byte)0);
		}

		// TODO: need bool array?
	}
	public static Bool bool() {
		return new Bool();
	}

	public static class StructField<T extends Struct> extends Field {

		public StructField(T struct) {
			super(struct.bytes());
		}

		public MemoryAddress addressOf(MemoryAddress addr) {
			return addr.addOffset(offset);
		}
	}
	public static <T extends Struct> StructField<T> struct(T struct) {
		return new StructField<>(struct);
	}

	public enum Precision {

		Float32(4, MemoryHandles.varHandle(float.class, ByteOrder.nativeOrder())) {

			@Override
			public Object fromDouble(double val) {
				return (float)val;
			}

			@Override
			public double toDouble(Object val) {
				return (double)(Float)val;
			}
		},

		Float64(8, MemoryHandles.varHandle(double.class, ByteOrder.nativeOrder())) {

			@Override
			public Object fromDouble(double val) {
				return val;
			}

			@Override
			public double toDouble(Object val) {
				return (Double)val;
			}
		};

		public final int bytes;
		public final VarHandle handle;

		Precision(int bytes, VarHandle handle) {
			this.bytes = bytes;
			this.handle = handle;
		}

		public abstract Object fromDouble(double val);
		public abstract double toDouble(Object val);

		public double cast(double val) {
			return toDouble(fromDouble(val));
		}

		public <T> T map(T f32, T f64) {
			return switch (this) {
				case Float32 -> f32;
				case Float64 -> f64;
			};
		}
	}

	public static class Real extends Field {

		public final Precision precision;

		private Real(Precision precision) {
			super(precision.bytes);
			this.precision = precision;
		}

		public double get(MemoryAddress addr) {
			return (double)precision.handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, double value) {
			precision.handle.set(addr.addOffset(offset), precision.fromDouble(value));
		}

		public static class Array extends Structs.Array {

			public final Precision precision;

			public Array(Precision precision) {
				super(precision.bytes);
				this.precision = precision;
			}

			public double get(MemoryAddress addr, long i) {
				return (double)precision.handle.get(addr.addOffset(i*precision.bytes));
			}

			public void set(MemoryAddress addr, long i, double value) {
				precision.handle.set(addr.addOffset(i*precision.bytes), value);
			}
		}
	}
	public static Real real(Precision precision) {
		return new Real(precision);
	}
	public static Real.Array realarray(Precision precision) {
		return new Real.Array(precision);
	}


	public static class Char8 extends Field {

		public static final long bytes = 1;
		private static final VarHandle handle = MemoryHandles.varHandle(byte.class, ByteOrder.nativeOrder());

		public Char8() {
			super(bytes);
		}

		public char get(MemoryAddress addr) {
			return (char)handle.get(addr.addOffset(offset));
		}

		public void set(MemoryAddress addr, char value) {
			handle.set(addr.addOffset(offset), value);
		}

		public static class Array extends Structs.Array {

			public Array() {
				super(bytes);
			}

			public char get(MemoryAddress addr, long i) {
				return (char)handle.get(addr.addOffset(i*bytes));
			}

			public void set(MemoryAddress addr, long i, double value) {
				handle.set(addr.addOffset(i*bytes), value);
			}

			public String getNullTerminated(MemoryAddress addr, int maxLen) {
				byte[] strbuf = new byte[maxLen];
				for (int i=0; i<maxLen; i++) {
					strbuf[i] = (byte)handle.get(addr.addOffset(i*bytes));
					if (strbuf[i] == 0) {
						return new String(strbuf, 0, i, Charsets.US_ASCII);
					}
				}
				return new String(strbuf, Charsets.US_ASCII);
			}

			// TODO: set?
		}
	}
	public static Char8 char8() {
		return new Char8();
	}
	public static Char8.Array char8array() {
		return new Char8.Array();
	}
}
