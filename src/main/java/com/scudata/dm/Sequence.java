package com.scudata.dm;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.ibm.icu.text.Collator;
import com.scudata.cellset.ICellSet;
import com.scudata.common.ByteArrayInputRecord;
import com.scudata.common.ByteArrayOutputRecord;
import com.scudata.common.Escape;
import com.scudata.common.IRecord;
import com.scudata.common.IntArrayList;
import com.scudata.common.MessageManager;
import com.scudata.common.RQException;
import com.scudata.common.Sentence;
import com.scudata.dm.comparator.*;
import com.scudata.dm.op.IGroupsResult;
import com.scudata.dw.IFilter;
import com.scudata.expression.CurrentElement;
import com.scudata.expression.Expression;
import com.scudata.expression.FieldId;
import com.scudata.expression.FieldRef;
import com.scudata.expression.Gather;
import com.scudata.expression.Node;
import com.scudata.expression.UnknownSymbol;
import com.scudata.expression.ValueList;
import com.scudata.expression.operator.And;
import com.scudata.expression.operator.DotOperator;
import com.scudata.expression.operator.Equals;
import com.scudata.expression.operator.Greater;
import com.scudata.expression.operator.NotEquals;
import com.scudata.expression.operator.NotGreater;
import com.scudata.expression.operator.NotSmaller;
import com.scudata.expression.operator.Or;
import com.scudata.expression.operator.Smaller;
import com.scudata.resources.EngineMessage;
import com.scudata.thread.MultithreadUtil;
import com.scudata.util.CursorUtil;
import com.scudata.util.MaxHeap;
import com.scudata.util.MinHeap;
import com.scudata.util.Variant;

/**
 * �����࣬Ԫ����Ŵ�1��ʼ����
 * @author WangXiaoJun
 *
 */
public class Sequence implements Externalizable, IRecord, Comparable<Sequence> {
	private static final long serialVersionUID = 0x02010003;
	protected ListBase1 mems; // ���г�Ա

	private static final char SEPARATOR = ','; // toStringʱ����Ԫ�طָ���
	private static final char STARTSYMBOL = '[';
	private static final char ENDSYMBOL = ']';
	private static final int SORT_HASH_LEN = 700; // ������С�ڴ���ֵ��id��group������������������hash

	/**
	 * ���ڼ������к���ʱ����ѹջ
	 * @author WangXiaoJun
	 *
	 */
	public class Current implements IComputeItem {
		private int curIndex; // ��ǰ�������ڽ��м����Ԫ�ص���������1��ʼ����
		private boolean isInStack = true; // �Ƿ��ڼ����ջ��

		public Current() {
		}
		
		public Current(int index) {
			curIndex = index;
		}

		/**
		 * ���ص�ǰ���ڼ����Ԫ��
		 * @return Object
		 */
		public Object getCurrent() {
			return mems.get(curIndex);
		}

		/**
		 * ���ص�ǰ���ڼ����Ԫ����������1��ʼ����
		 * @return int
		 */
		public int getCurrentIndex() {
			return curIndex;
		}
		
		/**
		 * ȡԴ����
		 */
		public Sequence getCurrentSequence() {
			return Sequence.this;
		}
		
		/**
		 * �ж������Ƿ��ڶ�ջ��
		 */
		public boolean isInStack(ComputeStack stack) {
			return isInStack;
		}
		
		/**
		 * ������ɣ����г�ջ
		 */
		public void popStack() {
			isInStack = false;
		}
		
		/**
		 * �жϵ�ǰ�����Ƿ�͸���������ͬһ������
		 * @param seq
		 * @return
		 */
		public boolean equalSequence(Sequence seq) {
			return Sequence.this == seq;
		}

		/**
		 * ȡ���еĳ���
		 * @return
		 */
		public int length() {
			return mems.size();
		}

		/**
		 * �����ȡ���еĳ�Ա
		 * @param i ��ţ���1��ʼ����
		 * @return
		 */
		public Object get(int i) {
			return mems.get(i);
		}

		/**
		 * ���õ�ǰ���ڼ����Ԫ������
		 * @param index int ��1��ʼ����
		 */
		public void setCurrent(int index) {
			this.curIndex = index;
		}

		/**
		 * �޸����еĵ�ǰԪ��Ϊָ��ֵ
		 * @param val
		 */
		public void assign(Object val) {
			mems.set(curIndex, val);
		}

		/**
		 * �޸�����ָ��λ�õ�Ԫ��
		 * @param index ��ţ���1��ʼ����
		 * @param val
		 */
		public void assign(int index, Object val) {
			mems.set(index, val);
		}
	}

	/**
	 * ����һ��������
	 */
	public Sequence() {
		mems = new ListBase1();
	}

	/**
	 * ����һ��ָ������������
	 * @param initialCapacity int ����
	 */
	public Sequence(int initialCapacity) {
		mems = new ListBase1(initialCapacity);
	}

	/**
	 * ����һ����ָ����Ա���ɵ�����
	 * @param v ��Ա����
	 */
	public Sequence(Object[] v) {
		if (v == null) {
			mems = new ListBase1();
		} else {
			mems = new ListBase1(v);
		}
	}

	/**
	 * ����һ�����У�������ӵ���Լ��ĳ�Ա
	 * @param seq Sequence
	 */
	public Sequence(Sequence seq) {
		if (seq == null) {
			mems = new ListBase1();
		} else {
			mems = new ListBase1(seq.mems);
		}
	}

	/**
	 * ����һ���������䣬���start����end����һ���ݼ�����������
	 * @param start int ��ʼֵ
	 * @param end int ����ֵ
	 */
	public Sequence(int start, int end) {
		if (start < end) {
			ListBase1 mems = new ListBase1(end - start + 1);
			this.mems = mems;
			for (; start <= end; ++start) {
				mems.add(new Integer(start));
			}
		} else {
			ListBase1 mems = new ListBase1(start - end + 1);
			this.mems = mems;
			for (; start >= end; --start) {
				mems.add(new Integer(start));
			}
		}
	}

	/**
	 * ��ָ�������ֵ��������
	 * @param start ��ʼֵ������
	 * @param end ����ֵ������
	 */
	public Sequence(long start, long end) {
		if (start < end) {
			long len = end - start + 1;
			if (len > Integer.MAX_VALUE) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("to" + mm.getMessage("function.invalidParam"));
			}

			ListBase1 mems = new ListBase1((int)len);
			this.mems = mems;
			for (; start <= end; ++start) {
				mems.add(new Long(start));
			}
		} else {
			long len = start - end + 1;
			if (len > Integer.MAX_VALUE) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("to" + mm.getMessage("function.invalidParam"));
			}

			ListBase1 mems = new ListBase1((int)len);
			this.mems = mems;
			for (; start >= end; --start) {
				mems.add(new Long(start));
			}
		}
	}

	/**
	 * ȡ���еĳ�Ա�б������ڱ��������Ż�
	 * @return
	 */
	public ListBase1 getMems() {
		return mems;
	}

	public void setMems(ListBase1 mems) {
		this.mems = mems;
		rebuildIndexTable();
	}

	/**
	 * �������еĹ�ϣֵ
	 */
	public int hashCode() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) return 0;

		Object obj = mems.get(1);
		int hash = obj != null ? obj.hashCode() : 0;
		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj != null) {
				hash = 31 * hash + obj.hashCode();
			} else {
				hash = 31 * hash;
			}
		}

		return hash;
	}

	/**
	 * ʹ���е�������С��minCapacity
	 * @param minCapacity ��С����
	 */
	public void ensureCapacity(int minCapacity) {
		mems.ensureCapacity(minCapacity);
	}

	/**
	 * ����Ԫ���״γ��ֵ�λ�ã�����������򷵻�0
	 * @param obj Object
	 * @return int
	 */
	public int firstIndexOf(Object obj) {
		int index = mems.firstIndexOf(obj);
		return index > 0 ? index : 0;
	}

	/**
	 * ����Ԫ�������ֵ�λ�ã�����������򷵻�0
	 * @param obj Object
	 * @return int
	 */
	public int lastIndexof(Object obj) {
		int index = mems.lastIndexOf(obj);
		return index > 0 ? index : 0;
	}

	/**
	 * ���еĳ�Աֻ������ͨ�����ݣ����ܰ�����¼
	 * @throws IOException
	 * @return byte[]
	 */
	public byte[] serialize() throws IOException {
		ByteArrayOutputRecord out = new ByteArrayOutputRecord();
		out.writeRecord(mems);
		return out.toByteArray();
	}

	/**
	 * ���еĳ�Աֻ������ͨ�����ݣ����ܰ�����¼
	 * @param buf byte[]
	 * @throws IOException
	 * @throws ClassNotFoundException
	 */
	public void fillRecord(byte[] buf) throws IOException,
		ClassNotFoundException {
		ByteArrayInputRecord in = new ByteArrayInputRecord(buf);
		mems = (ListBase1)in.readRecord(new ListBase1());
	}

	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeByte(1); // �汾��
		out.writeObject(mems);
	}

	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		in.readByte(); // �汾��
		mems = (ListBase1)in.readObject();
	}

	/**
	 * ɾ�����еĳ�Ա�����ҵ�������
	 */
	public void reset() {
		mems.clear();
	}

	/**
	 * ɾ�����еĳ�Ա
	 */
	public void clear() {
		mems.clear();
	}

	/**
	 * �������е�������ʹ����Ԫ�������
	 */
	public void trimToSize() {
		mems.trimToSize();
	}

	/**
	 * ת��
	 * @param c int ����
	 * @return Sequence
	 */
	public Sequence transpose(int c) {
		ListBase1 mems = this.mems;
		int len = mems.size();

		int r = len / c;
		if (len % c != 0) {
			r++;
		}

		Sequence result = new Sequence(len);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= c; ++i) {
			for (int j = 0; j < r; ++j) {
				int index = j * c + i;
				if (index <= len) {
					resultMems.add(mems.get(index));
				} else {
					resultMems.add(null);
				}
			}
		}

		return result;
	}

	/**
	 * ��������Ԫ�ظ���
	 * @return int
	 */
	public int length() {
		return mems.size();
	}

	/**
	 * ȡ���зǿ�Ԫ�ظ���
	 * @param opt String
	 * @return int
	 */
	public int count(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		int count = size;

		for (int i = 1; i <= size; ++i) {
			if (Variant.isFalse(mems.get(i))) {
				count--;
			}
		}

		return count;
	}

	/**
	 * ÿ��interval����ȡseqsָ����Ԫ��
	 * @param interval int ���
	 * @param seqs int[] Ԫ������
	 * @return Sequence
	 */
	public Sequence step(int interval, int[] seqs) {
		if (interval < 1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(interval + mm.getMessage("engine.indexOutofBound"));
		}
		
		int count = seqs.length;
		for (int i = 0; i < count; ++i) {
			// ���ټ���Ƿ��interval���ˣ�������������ǰ���
			if (seqs[i] < 1) { // || seqs[i] > interval
				MessageManager mm = EngineMessage.get();
				throw new RQException(seqs[i] + mm.getMessage("engine.indexOutofBound"));
			}
		}

		ListBase1 mems = this.mems;
		int srcLen = mems.size();
		Sequence result = new Sequence((srcLen / interval + 1) * count);
		ListBase1 resultMems = result.mems;

		for (int base = 0; ; base += interval) {
			boolean addOne = false;
			for (int i = 0; i < count; ++i) {
				int seq = base + seqs[i];
				if (seq <= srcLen) {
					resultMems.add(mems.get(seq));
					addOne = true;
				}
			}

			if (!addOne) break;
		}

		return result;
	}

	/**
	 * ȡ����ʹ����ʽΪ���Ԫ�ظ���
	 * @param exp ����ʽ
	 * @param opt
	 * @param ctx ����������
	 * @return
	 */
	public int count(Expression exp, String opt, Context ctx) {
		if (exp == null) {
			return count(opt);
		}

		int size = length();
		int count = size;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				Object obj = exp.calculate(ctx);
				if (Variant.isFalse(obj)) {
					count--;
				}
			}
		} finally {
			stack.pop();
		}

		return count;
	}

	/**
	 * �������е�Ԫ��
	 * @return Ԫ��ֵ��ɵ�����
	 */
	public Object[] toArray() {
		return mems.toArray();
	}

	/**
	 * ��Ԫ�����θ���a��������a
	 * @param a Object[]
	 * @return Object[]
	 */
	public Object[] toArray(Object a[]) {
		return mems.toArray(a);
	}

	/**
	 * ȡ��ĳһԪ��
	 * @param seq int ��1��ʼ����
	 * @return Object
	 */
	public Object get(int seq) {
		if (seq < 1 || seq > length()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(seq + mm.getMessage("engine.indexOutofBound"));
		}
		return mems.get(seq);
	}
	
	/**
	 * ȡ��ĳһԪ�أ����������źϷ���
	 * @param seq int ��1��ʼ����
	 * @return Object
	 */
	public Object getMem(int seq) {
		return mems.get(seq);
	}

	/**
	 * ȡĳһ����
	 * @param start int ��ʼλ�ã�������
	 * @param end int ����λ�ã���������
	 * @return Sequence �������
	 */
	public Sequence get(int start, int end) {
		Sequence seq = new Sequence(end - start);
		seq.mems.addSection(mems, start, end);
		return seq;
	}
	
	/**
	 * ���ض��Ԫ��
	 * @param seq Sequence Ԫ��λ�ù��ɵ����У�Ԫ��ֵ���������еĳ���
	 * @return Sequence
	 */
	public Sequence get(Sequence seq) {
		if (seq == null) {
			return new Sequence(0);
		}

		ListBase1 mems = this.mems;
		ListBase1 posMems = seq.mems;

		int size = mems.size();
		int posSize = posMems.size();
		Sequence result = new Sequence(posSize);

		for (int i = 1; i <= posSize; ++i) {
			Object obj = posMems.get(i);
			if (!(obj instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntSeries"));
			}

			int index = ((Number)obj).intValue();
			if (index < 1 || index > size) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
			}

			result.add(mems.get(index));
		}

		return result;
	}

	/**
	 * �Ƿ����ĳһԪ��
	 * @param obj Object Ԫ��ֵ
	 * @param isSorted boolean���������򣬽����ö��ֲ���
	 * @return boolean
	 */
	public boolean contains(Object obj, boolean isSorted) {
		if (isSorted) {
			return mems.binarySearch(obj) > 0;
		} else {
			return mems.contains(obj);
		}
	}

	// �����Ƿ�����������
	private boolean isIntInterval() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return false;
		}

		Object obj1 = mems.get(1);
		if (!(obj1 instanceof Number)) {
			return false;
		}
		if (size == 1) {
			return true;
		}

		Object obj2 = mems.get(2);
		if (!(obj2 instanceof Number)) {
			return false;
		}

		int prev = ((Number)obj2).intValue();
		int dif = prev - ((Number)obj1).intValue();

		if (dif == 1) { // ����
			for (int i = 3; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!(obj instanceof Number)) {
					return false;
				}

				prev++;
				if (((Number)obj).intValue() != prev) {
					return false;
				}
			}
		} else if (dif == -1) { // �ݼ�
			for (int i = 3; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!(obj instanceof Number)) {
					return false;
				}

				prev--;
				if (((Number)obj).intValue() != prev) {
					return false;
				}
			}
		} else if (dif == 0) { // ������ֻ��һ����
			if (size != 2) {
				return false;
			}
		} else {
			return false;
		}

		return true;
	}

	// ���������Ƿ���n�û�
	private boolean isPermutation(int n) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		if (len != n) return false;

		boolean[] sign = new boolean[n + 1];
		for (int i = 1; i <= len; ++i) {
			Object obj = mems.get(i);
			if (!(obj instanceof Number)) {
				return false;
			}

			// ��������[1��n]��Χ�ڣ�������ظ���Ԫ��
			int tmp = ( (Number) obj).intValue();
			if (tmp < 1 || tmp > n || sign[tmp]) {
				return false;
			}

			sign[tmp] = true;
		}

		return true;
	}

	/**
	 * ���������Ƿ��м�¼
	 * @return boolean
	 */
	public boolean hasRecord() {
		ListBase1 mems = this.mems;
		for (int i = 1, size = mems.size(); i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Record) {
				return true;
			}
		}

		return false;
	}

	/**
	 * ���ص�ǰ�����Ƿ�������
	 * @return boolean true�������У�false��������
	 */
	public boolean isPmt() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		boolean hasRecord = false;

		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				if (obj instanceof Record) {
					hasRecord = true;
				} else {
					return false;
				}
			}
		}

		return hasRecord;
	}

	/**
	 * ���ص�ǰ�����Ƿ��Ǵ�����
	 * @return boolean true���Ǵ����У��ṹ��ͬ��
	 */
	public boolean isPurePmt() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return false;
		}

		Object obj = mems.get(1);
		if (!(obj instanceof Record)) {
			return false;
		}
		
		DataStruct ds = ((Record)obj).dataStruct();
		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (!(obj instanceof Record) ||
				!ds.isCompatible(((Record)obj).dataStruct())) {
				return false;
			}
		}

		return true;
	}

	/**
	 * ����λ���е������У����س���Ϊn��λ����
	 * @param n int
	 * @return Sequence
	 */
	public Sequence inv(int n) {
		if (n < 0) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("inv" + mm.getMessage("function.invalidParam"));
		}

		ListBase1 mems = this.mems;
		Integer[] seqs = new Integer[n];
		for (int i = 1, len = mems.size(); i <= len; ++i) {
			Object obj = mems.get(i);
			if (!(obj instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntSeries"));
			}

			int pos = ((Number)obj).intValue();
			if (pos > 0 && pos <= n) {
				if (seqs[pos - 1] == null) {
					seqs[pos - 1] = new Integer(i);
				}
			}
		}

		Integer int0 = new Integer(0);
		for (int i = 0; i < n; ++i) {
			if (seqs[i] == null)seqs[i] = int0;
		}

		return new Sequence(seqs);
	}

	/**
	 * ����λ�����з�תԴ����
	 * @param seq Sequence λ������
	 * @param opt String o���ı�Դ����
	 * @return Sequence
	 */
	public Sequence inv(Sequence seq, String opt) {
		ListBase1 mems = this.mems;
		ListBase1 posMems = seq.mems;
		int n = mems.size();
		int len = posMems.size();

		boolean isNew = opt == null || opt.indexOf('o') == -1;
		if (!isNew && this instanceof Table) {
			if (!seq.isPermutation(n)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("inv" + mm.getMessage("function.invalidParam"));
			}
		}

		Object[] objs = new Object[n];
		for (int i = 1; i <= len; ++i) {
			Object obj = posMems.get(i);
			if (!(obj instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntSeries"));
			}

			int pos = ((Number)obj).intValue();
			if (pos > 0 && pos <= n) {
				objs[pos - 1] = mems.get(i);
			}
		}

		if (isNew) {
			return new Sequence(objs);
		} else {
			mems.clear();
			mems.addAll(objs);
			return this;
		}
	}
	
	/**
	 * �ж�seq�Ƿ��ǵ�ǰ���е��û���
	 * @param seq Sequence
	 * @return boolean
	 */
	public boolean isPeq(Sequence seq) {
		if (seq == null) {
			return false;
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int size = mems.size();
		int size2 = mems2.size();
		if (size != size2) {
			return false;
		}

		boolean[] founds = new boolean[size + 1];
		nextCand:
		for (int t = 1; t <= size2; ++t) {
			Object obj = mems2.get(t);
			for (int s = 1; s <= size; ++s) {
				if (!founds[s] && Variant.isEquals(mems.get(s), obj)) {
					// �ҵ����������ѭ��������
					founds[s] = true;
					continue nextCand;
				}
			}
			
			// û���ҵ�����false
			return false;
		}
		
		return true;
	}

	/**
	 * �������еķ�ת����
	 * @return Sequence
	 */
	public Sequence rvs() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = size; i > 0; --i) {
			resultMems.add(mems.get(i));
		}

		return result;
	}

	/**
	 * ����ʹ�����еĲ�����Ϊ�����е�n�û�
	 * @param opt String z������
	 * @return Sequence
	 */
	public Sequence psort(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}
		
		boolean isDesc = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('z') != -1)isDesc = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
		}

		int len = size + 1;
		PSortItem[] infos = new PSortItem[len];
		for (int i = 1; i < len; ++i) {
			infos[i] = new PSortItem(i, mems.get(i));
		}

		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = new BaseComparator_0();
		} else {
			comparator = new BaseComparator();
		}

		if (isDesc) {
			comparator = new DescComparator(comparator);
		}
		
		// ��������
		MultithreadUtil.sort(infos, 1, len, new PSortComparator(comparator));
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i < len; ++i) {
			resultMems.add(infos[i].index);
		}
		
		return result;
	}

	/**
	 * ����ʹ�����а�����ʽ�������Ĳ�����Ϊ�����е�n�û�
	 * @param exp Expression �������ʽ
	 * @param opt String z������
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence psort(Expression exp, String opt, Context ctx) {
		return calc(exp, opt, ctx).psort(opt);
	}

	/**
	 * ����ʹ�����еĲ�����Ϊ�����е�n�û�
	 * @param exps Expression[] ����ʽ����
	 * @param opt String z������
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence psort(Expression []exps, String opt, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}
		
		boolean isDesc = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('z') != -1)isDesc = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
		}

		int len = size + 1;
		int fcount = exps.length;
		PSortItem[] infos = new PSortItem[len];
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i < len; ++i) {
				current.setCurrent(i);
				Object []vals = new Object[fcount];
				for (int f = 0; f < fcount; ++f) {
					vals[f] = exps[f].calculate(ctx);
				}
				
				infos[i] = new PSortItem(i, vals);
			}
		} finally {
			stack.pop();
		}

		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = new ArrayComparator_0(fcount);
		} else {
			comparator = new ArrayComparator(fcount);
		}
		
		if (isDesc) {
			comparator = new DescComparator(comparator);
		}

		// ��������
		MultithreadUtil.sort(infos, 1, len, new PSortComparator(comparator));
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i < len; ++i) {
			resultMems.add(infos[i].index);
		}
		
		return result;
	}

	/**
	 * ���ն����ʽ�Ͷ�˳������, ����n�û�
	 * @param exps Expression[] ����ʽ����
	 * @param orders int[] ˳������, 1����, -1����, 0ԭ��
	 * @param opt String
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence psort(Expression[] exps, int[] orders, String opt, Context ctx) {
		if (length() == 0) {
			return new Sequence(0);
		}

		if (exps == null || orders == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("psort" + mm.getMessage("function.paramValNull"));
		}

		int cols = exps.length;
		if (orders.length != cols) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("psort" + mm.getMessage("function.paramCountNotMatch"));
		}

		int zeroCount = 0;
		for (int i = 0; i < cols; ++i) {
			if (orders[i] == 0) {
				zeroCount++;
			}
		}

		if (zeroCount == cols) {
			return new Sequence(1, length());
		}

		int cmpCount = cols - zeroCount;
		Sequence[] values = new Sequence[cmpCount];
		Comparator<Object>[] comparators = new Comparator[cmpCount];
		Comparator<Object> baseCmp = new BaseComparator();
		if (opt == null || opt.indexOf('0') == -1) {
			baseCmp = new BaseComparator();
		} else {
			baseCmp = new BaseComparator_0();
		}

		Comparator<Object> ascCmp = new PSortComparator(baseCmp);
		Comparator<Object> descCmp = new PSortComparator(new DescComparator(baseCmp));
		
		// �������еı���ʽ
		for (int i = 0, col = 0; i < cols; ++i) {
			if (orders[i] != 0) {
				values[col] = calc(exps[i], opt, ctx);
				comparators[col] = orders[i] > 0 ? ascCmp : descCmp;
				col++;
			}
		}

		ListBase1 result0 = values[0].mems;
		int size = result0.size();
		PSortItem[] infos = new PSortItem[size + 1];
		for (int i = 1; i <= size; ++i) {
			infos[i] = new PSortItem(i, result0.get(i));
		}

		sort(infos, values, comparators, 0, 1, size + 1);
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= size; ++i) {
			resultMems.add(infos[i].index);
		}

		return result;
	}

	/**
	 * �����ɴ�����Ԫ�ع��ɵĵ�����
	 * @param opt z������o���ı�ԭ����
	 * @return Sequence
	 */
	public Sequence sort(String opt) {
		if (length() == 0) {
			if (this instanceof Table || (opt != null && opt.indexOf('o') != -1)) {
				return this;
			} else {
				return new Sequence(0);
			}
		}
		
		boolean bDesc = false, bOrg = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1) {
				return sort_u();
			}
			
			if (opt.indexOf('z') != -1)bDesc = true;
			if (opt.indexOf('o') != -1)bOrg = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
			
			if (opt.indexOf('n') != -1) {
				Sequence seq = group_n("s");
				if (bOrg) {
					mems = seq.mems;
					return this;
				} else {
					return seq;
				}
			}
		}

		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = new BaseComparator_0();
		} else {
			comparator = new BaseComparator();
		}

		if (bDesc) {
			comparator = new DescComparator(comparator);
		}
		
		if (bOrg) {
			mems.sort(comparator);
			return this;
		} else {
			Sequence result = new Sequence(this);
			result.mems.sort(comparator);
			return result;
		}
	}

	/**
	 * ȡ�Ƚ���
	 * @param loc ���������ַ����ıȽϣ����Ϊ�����ò���StringĬ�ϵıȽϷ���
	 * @param throwExcept �����޷��Ƚ�ʱ�Ƿ��׳��쳣
	 * @return
	 */
	public static Comparator<Object> getComparator(String loc, boolean throwExcept) {
		if (loc == null || loc.length() == 0) {
			return new BaseComparator(throwExcept);
		} else {
			Locale locale = parseLocale(loc);
			return new LocaleComparator(Collator.getInstance(locale), throwExcept);
		}
	}
	
	private static Comparator<Object> getComparator_0(String loc) {
		if (loc == null || loc.length() == 0) {
			return new BaseComparator_0();
		} else {
			Locale locale = parseLocale(loc);
			return new LocaleComparator_0(Collator.getInstance(locale));
		}
	}

	private static Locale parseLocale(String loc) {
		int index = loc.indexOf('_');
		if (index == -1) return new Locale(loc);

		String language = loc.substring(0, index);
		index++;
		int index2 = loc.indexOf('_', index);
		if (index2 == -1) {
			String country = loc.substring(index);
			return new Locale(language, country);
		} else {
			String country = loc.substring(index, index2);
			String variant = loc.substring(index2 + 1);
			return new Locale(language, country, variant);
		}
	}

	/**
	 * �����ɴ�����Ԫ�ع��ɵĵ�����
	 * @param loc String ����
	 * @param opt z������o���ı�ԭ����
	 * @return Sequence
	 */
	public Sequence sort(String loc, String opt) {
		if (length() == 0) {
			if (this instanceof Table || (opt != null && opt.indexOf('o') != -1)) {
				return this;
			} else {
				return new Sequence(0);
			}
		}
		
		boolean bDesc = false, bOrg = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1) {
				return sort_u();
			}

			if (opt.indexOf('z') != -1)bDesc = true;
			if (opt.indexOf('o') != -1)bOrg = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
			
			if (opt.indexOf('n') != -1) {
				Sequence seq = group_n("s");
				if (bOrg) {
					mems = seq.mems;
					return this;
				} else {
					return seq;
				}
			}
		}

		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = getComparator_0(loc);
		} else {
			comparator = getComparator(loc, true);
		}
		
		if (bDesc) {
			comparator = new DescComparator(comparator);
		}
		
		if (bOrg) {
			mems.sort(comparator);
			return this;
		} else {
			Sequence result = new Sequence(this);
			result.mems.sort(comparator);
			return result;
		}
	}

	private Sequence sort_u() {
		ListBase1 mems = this.mems;
		int len = mems.size();
		if (len == 0) return new Sequence(0);
		
		Sequence result = new Sequence(len);
		ListBase1 rm = result.mems;
		boolean []signs = new boolean[len + 1];
		
		for (int i = 1; i <= len; ++i) {
			if (!signs[i]) {
				Object obj = mems.get(i);
				rm.add(obj);
				for (int j = i + 1; j <= len; ++j) {
					Object obj2 = mems.get(j);
					if (!signs[j] && Variant.isEquals(obj, obj2)) {
						signs[j] = true;
						rm.add(obj2);
					}
				}
			}
		}
		
		return result;
	}
	
	private Sequence sort_u(Expression exp, Context ctx) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		
		Sequence values = calc(exp, ctx);
		ListBase1 valMems = values.mems;
		Sequence result = new Sequence(len);
		ListBase1 rm = result.mems;
		boolean []signs = new boolean[len + 1];
		
		for (int i = 1; i <= len; ++i) {
			if (!signs[i]) {
				Object obj = valMems.get(i);
				rm.add(mems.get(i));
				for (int j = i + 1; j <= len; ++j) {
					if (!signs[j] && Variant.isEquals(obj, valMems.get(j))) {
						signs[j] = true;
						rm.add(mems.get(j));
					}
				}
			}
		}
		
		return result;
	}
	
	private Sequence sort_u(Expression []exps, Context ctx) {
		if (exps == null || exps.length == 0) {
			return sort_u();
		} else if (exps.length == 1) {
			return sort_u(exps[0], ctx);
		}
		
		int len = length();
		if (len == 0) return new Sequence(0);
		
		Sequence result = this;
		int fcount = exps.length;
		ListBase1 []valMems = new ListBase1[fcount];
		for (int f = 0; f < fcount; ++f) {
			Sequence tmp = new Sequence(len);
			ListBase1 tmpMems = tmp.mems;
			ListBase1 mems = result.mems;
			boolean []signs = new boolean[len + 1];
			
			// Ԫ��λ���ڱ䶯����Ҫ���¼������ʽֵ
			for (int c = 0; c <= f; ++c) {
				valMems[c] = result.calc(exps[c], ctx).mems;
			}
			
			for (int i = 1; i <= len; ++i) {
				if (!signs[i]) {
					tmpMems.add(mems.get(i));
					
					Next:
					for (int j = i + 1; j <= len; ++j) {
						if (!signs[j]) {
							for (int c = 0; c <= f; ++c) {
								if (!Variant.isEquals(valMems[c].get(i), valMems[c].get(j))) {
									continue Next;
								}
							}
							
							signs[j] = true;
							tmpMems.add(mems.get(j));
						}
					}
				}
			}
			
			result = tmp;
		}
		
		return result;
	}
	
	/**
	 * ����Ԫ����Դ�����е�λ�÷��ص�ǰ���е�Ԫ�ء�
	 * @param sequences Դ��������
	 * @param vals �����ҵ�Ԫ������
	 * @param opt a��������������������Ԫ�أ�b��Դ��������
	 * @return
	 */
	public Object lookup(Sequence[] sequences, Object[] vals, String opt) {
		if (sequences == null || vals == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("lookup" + mm.getMessage("function.invalidParam"));
		}

		int gcount = sequences.length;
		if (gcount == 0 || gcount != vals.length) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("lookup" + mm.getMessage("function.invalidParam"));
		}

		boolean bAll = false, isSorted = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true;
			if (opt.indexOf('b') != -1)isSorted = true;
		}

		int srcLen = sequences[0].length();
		if (srcLen == 0)return bAll ? new Sequence(0) : null;

		Comparator<Object> baseCmp = new BaseComparator();
		Sequence seq = (Sequence)sequences[0].firstIndexOf(vals[0], baseCmp, true,
			isSorted, false, 1, srcLen);
		if (seq.length() == 0) {
			return bAll ? new Sequence(0) : null;
		}

		for (int i = 1; i < gcount; ++i) {
			srcLen = sequences[i].length();
			if (srcLen < 1)return bAll ? new Sequence(0) : null;
			Sequence tmp = (Sequence)sequences[i].firstIndexOf(vals[i], baseCmp, true,
				isSorted, false, 1, srcLen);

			seq = seq.isect(tmp, true);
			if (seq.length() == 0) {
				return bAll ? new Sequence(0) : null;
			}
		}

		ListBase1 posMems = seq.mems;
		ListBase1 mems = this.mems;
		int len = mems.size();
		if (bAll) {
			int posCount = posMems.size();
			Sequence result = new Sequence(posCount);

			for (int i = 1; i <= posCount; ++i) {
				int pos = ((Integer)posMems.get(i)).intValue();
				if (pos <= len) {
					result.add(mems.get(pos));
				} else {
					result.add(null);
				}
			}

			return result;
		} else {
			int pos = ((Integer)posMems.get(1)).intValue();
			if (pos <= len) {
				return mems.get(pos);
			} else {
				return null;
			}
		}
	}

	/**
	 * �����������еĺ���(����)+
	 * @param seq Sequence
	 * @param bMerge boolean true: ����������ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence conj(Sequence seq, boolean bMerge) {
		if (seq == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("conj" + mm.getMessage("function.paramValNull"));
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();

		Sequence result = new Sequence(len + len2);
		ListBase1 resultMems = result.mems;

		if (bMerge) {
			// �鲢��������
			int s = 1, t = 1;
			while (s <= len && t <= len2) {
				Object obj = mems.get(s);
				Object obj2 = mems2.get(t);
				if (Variant.compare(obj, obj2, true) > 0) {
					resultMems.add(obj2);
					t++;
				} else {
					resultMems.add(obj);
					s++;
				}
			}

			// ��ʣ���Ԫ�����ӵ�������
			while (s <= len) {
				resultMems.add(mems.get(s));
				s++;
			}

			while (t <= len2) {
				resultMems.add(mems2.get(t));
				t++;
			}
		} else {
			resultMems.addAll(mems);
			resultMems.addAll(mems2);
		}
		
		return result;
	}

	/**
	 * �����������еĲ���-
	 * @param seq Sequence
	 * @param bMerge boolean true: ����������ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence diff(Sequence seq, boolean bMerge) {
		if (seq == null || seq.length() == 0) {
			return new Sequence(this);
		}
		
		if (!bMerge) {
			return CursorUtil.diff(this, seq);
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		Sequence result = new Sequence(len);
		ListBase1 resultMems = result.mems;

		// �鲢������
		int s = 1, t = 1;
		while (s <= len && t <= len2) {
			Object obj = mems.get(s);
			int cmp = Variant.compare(obj, mems2.get(t), true);
			if (cmp < 0) {
				resultMems.add(obj);
				s++;
			} else if (cmp == 0) {
				t++;
				s++;
			} else {
				t++;
			}
		}

		// ʣ���Ԫ�ض�û��seq��
		while (s <= len) {
			resultMems.add(mems.get(s));
			s++;
		}

		return result;
	}

	/**
	 * �����������еĽ���
	 * @param seq Sequence
	 * @param bMerge boolean true: ����������ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence isect(Sequence seq, boolean bMerge) {
		if (seq == null || seq.length() == 0) {
			return new Sequence();
		}

		if (!bMerge) {
			return CursorUtil.isect(this, seq);
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		Sequence result = new Sequence(len > len2 ? len2 : len);
		ListBase1 resultMems = result.mems;

		// �鲢������
		int s = 1, t = 1;
		while (s <= len && t <= len2) {
			Object obj = mems.get(s);
			int cmp = Variant.compare(obj, mems2.get(t), true);
			if (cmp < 0) {
				s++;
			} else if (cmp == 0) {
				resultMems.add(obj);
				t++;
				s++;
			} else {
				t++;
			}
		}

		return result;
	}

	/**
	 * �����������еĲ���
	 * @param seq Sequence
	 * @param bMerge boolean true: ����������ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence union(Sequence seq, boolean bMerge) {
		if (seq == null || seq.length() == 0) {
			return new Sequence(this);
		}

		if (!bMerge) {
			return CursorUtil.union(this, seq);
		}
		
		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		Sequence result = new Sequence(len + len2);
		ListBase1 resultMems = result.mems;

		// �鲢������
		int s = 1, t = 1;
		while (s <= len && t <= len2) {
			Object obj = mems.get(s);
			Object obj2 = mems2.get(t);
			int cmp = Variant.compare(obj, obj2, true);
			if (cmp < 0) {
				resultMems.add(obj);
				s++;
			} else if (cmp == 0) {
				resultMems.add(obj);
				t++;
				s++;
			} else {
				resultMems.add(obj2);
				t++;
			}
		}

		// ��ʣ���Ԫ�����ӵ�������
		while (s <= len) {
			resultMems.add(mems.get(s));
			s++;
		}

		while (t <= len2) {
			resultMems.add(mems2.get(t));
			t++;
		}

		return result;
	}

	private static Sequence mergeConj(Sequence []sequences) {
		int count = sequences.length;
		ListBase1 []lists = new ListBase1[count];
		int []lens = new int[count];
		int total = 0;
		Comparator<Object> c = null;

		for (int i = 0; i < count; ++i) {
			if (sequences[i] == null) {
				sequences[i] = new Sequence(0);
			}

			lists[i] = sequences[i].mems;
			lens[i] = lists[i].size();
			total += lens[i];

			if (c == null && lens[i] > 0) {
				if (sequences[i].ifn() instanceof Record) {
					c = new RecordKeyComparator();
				} else {
					c = new BaseComparator();
				}
			}
		}

		if (total == 0) {
			return null;
		}

		if (count == 2) {
			ListBase1 list1 = lists[0];
			ListBase1 list2 = lists[1];
			int len1 = lens[0];
			int len2 = lens[1];
			Sequence result = new Sequence(total);
			ListBase1 resultList = result.mems;

			if (len1 == 0) {
				resultList.addAll(list2);
			} else if (len2 == 0) {
				resultList.addAll(list1);
			} else if (c.compare(list1.get(len1), list2.get(1)) <= 0) {
				resultList.addAll(list1);
				resultList.addAll(list2);
			} else if (c.compare(list2.get(len2), list1.get(1)) <= 0) {
				resultList.addAll(list2);
				resultList.addAll(list1);
			} else {
				// �鲢��������
				int s1 = 1, s2 = 1;
				while (s1 <= len1 && s2 <= len2) {
					Object obj1 = list1.get(s1);
					Object obj2 = list2.get(s2);

					int cmp = c.compare(obj1, obj2);
					if (cmp == 0) {
						resultList.add(obj1);
						resultList.add(obj2);
						s1++;
						s2++;
					} else if (cmp > 0) {
						resultList.add(obj2);
						s2++;
					} else {
						resultList.add(obj1);
						s1++;
					}
				}

				// ��ʣ���Ԫ�����ӵ�������
				if (s1 <= len1) {
					resultList.addSection(list1, s1);
				} else {
					resultList.addSection(list2, s2);
				}
			}

			return result;
		} else {
			// �������Ż���
			Object []itemVals = new Object[count]; // ���еĵ�ǰԪ��
			int []items = new int[count]; // ���еĵ�ǰԪ�ش�С���������
			int []index = new int[count]; // ���еĵ�ǰλ��

			Next:
			for (int i = 0; i < count; ++i) {
				index[i] = 1;
				if (lens[i] == 0) {
					items[i] = -1;
					continue Next;
				}

				Object val = lists[i].get(1);
				itemVals[i] = val;
				for (int j = 0; j < i; ++j) {
					if (items[j] == -1) {
						items[j] = i;
						items[i] = -1;
						continue Next;
					} else if (c.compare(val, itemVals[items[j]]) < 0) {
						for (int k = i; k > j; --k) {
							items[k] = items[k - 1];
						}

						items[j] = i;
						continue Next;
					}
				}

				items[i] = i;
			}

			Sequence result = new Sequence(total);
			ListBase1 resultSeqList = result.mems;

			Next:
			for(int i = 1; i <= total; ++i) {
				int item = items[0];
				index[item]++;
				resultSeqList.add(itemVals[item]);
				if (index[item] <= lens[item]) {
					Object val = lists[item].get(index[item]);
					itemVals[item] = val;
					for (int j = 1; j < count; ++j) {
						if (items[j] == -1 || c.compare(val, itemVals[items[j]]) <= 0) {
							items[j - 1] = item;
							continue Next;
						} else {
							items[j - 1] = items[j];
						}
					}

					items[count - 1] = item;
				} else {
					for (int j = 1; j < count; ++j) {
						items[j - 1] = items[j];
					}

					itemVals[item] = null;
					items[count - 1] = -1;
				}
			}

			return result;
		}
	}

	private static Sequence mergeConj(Sequence []sequences, Expression exp, Context ctx) {
		if (exp == null) {
			return mergeConj(sequences);
		}

		int count = sequences.length;
		ListBase1 []lists = new ListBase1[count];
		Sequence []values = new Sequence[count];
		ListBase1 []valueLists = new ListBase1[count];
		int []lens = new int[count];
		int total = 0;

		for (int i = 0; i < count; ++i) {
			if (sequences[i] == null) {
				sequences[i] = new Sequence(0);
			}

			lists[i] = sequences[i].mems;
			values[i] = sequences[i].calc(exp, ctx);
			valueLists[i] = values[i].mems;

			lens[i] = lists[i].size();
			total += lens[i];
		}

		if (total == 0) {
			return null;
		}

		if (count == 2) {
			ListBase1 list1 = lists[0];
			ListBase1 list2 = lists[1];
			ListBase1 valueList1 = valueLists[0];
			ListBase1 valueList2 = valueLists[1];

			int len1 = lens[0];
			int len2 = lens[1];
			Sequence result = new Sequence(total);
			ListBase1 resultList = result.mems;

			if (len1 == 0) {
				resultList.addAll(list2);
			} else if (len2 == 0) {
				resultList.addAll(list1);
			} else if (Variant.compare(valueList1.get(len1), valueList2.get(1), true) <= 0) {
				resultList.addAll(list1);
				resultList.addAll(list2);
			} else if (Variant.compare(valueList2.get(len2), valueList1.get(1), true) <= 0) {
				resultList.addAll(list2);
				resultList.addAll(list1);
			} else {
				// �鲢��������
				int s1 = 1, s2 = 1;
				while (s1 <= len1 && s2 <= len2) {
					Object obj1 = valueList1.get(s1);
					Object obj2 = valueList2.get(s2);

					int cmp = Variant.compare(obj1, obj2, true);
					if (cmp == 0) {
						resultList.add(list1.get(s1));
						resultList.add(list2.get(s2));
						s1++;
						s2++;
					} else if (cmp > 0) {
						resultList.add(list2.get(s2));
						s2++;
					} else {
						resultList.add(list1.get(s1));
						s1++;
					}
				}

				// ��ʣ���Ԫ�����ӵ�������
				if (s1 <= len1) {
					resultList.addSection(list1, s1);
				} else {
					resultList.addSection(list2, s2);
				}
			}

			return result;
		} else {
			// �������Ż���
			Object []itemVals = new Object[count]; // ���еĵ�ǰԪ��
			int []items = new int[count]; // ���еĵ�ǰԪ�ش�С���������
			int []index = new int[count]; // ���еĵ�ǰλ��

			Next:
			for (int i = 0; i < count; ++i) {
				index[i] = 1;
				if (lens[i] == 0) {
					items[i] = -1;
					continue Next;
				}

				Object val = valueLists[i].get(1);
				itemVals[i] = val;
				for (int j = 0; j < i; ++j) {
					if (items[j] == -1) {
						items[j] = i;
						items[i] = -1;
						continue Next;
					} else if (Variant.compare(val, itemVals[items[j]], true) < 0) {
						for (int k = i; k > j; --k) {
							items[k] = items[k - 1];
						}

						items[j] = i;
						continue Next;
					}
				}

				items[i] = i;
			}

			Sequence result = new Sequence(total);
			ListBase1 resultList = result.mems;

			Next:
			for(int i = 1; i <= total; ++i) {
				int item = items[0];
				resultList.add(lists[item].get(index[item]));
				index[item]++;
				if (index[item] <= lens[item]) {
					Object val = valueLists[item].get(index[item]);
					itemVals[item] = val;
					for (int j = 1; j < count; ++j) {
						if (items[j] == -1 || Variant.compare(val, itemVals[items[j]], true) <= 0) {
							items[j - 1] = item;
							continue Next;
						} else {
							items[j - 1] = items[j];
						}
					}

					items[count - 1] = item;
				} else {
					for (int j = 1; j < count; ++j) {
						items[j - 1] = items[j];
					}

					itemVals[item] = null;
					items[count - 1] = -1;
				}
			}

			return result;
		}
	}

	private static Sequence mergeConj(Sequence []sequences, Expression []exps, Context ctx) {
		if (exps == null || exps.length == 0) {
			return mergeConj(sequences);
		} else if (exps.length == 1) {
			return mergeConj(sequences, exps[0], ctx);
		}

		int count = sequences.length;
		ListBase1 []lists = new ListBase1[count];
		int []lens = new int[count];
		int total = 0;

		for (int i = 0; i < count; ++i) {
			if (sequences[i] == null) {
				sequences[i] = new Sequence(0);
			}

			lists[i] = sequences[i].mems;
			lens[i] = lists[i].size();
			total += lens[i];
		}

		if (total == 0) {
			return null;
		}

		if (count == 2) {
			ListBase1 list1 = lists[0];
			ListBase1 list2 = lists[1];

			int len1 = lens[0];
			int len2 = lens[1];
			Sequence result = new Sequence(total);
			ListBase1 resultList = result.mems;

			if (len1 == 0) {
				resultList.addAll(list2);
				return result;
			} else if (len2 == 0) {
				resultList.addAll(list1);
				return result;
			}

			Object []values1 = new Object[exps.length];
			Object []values2 = new Object[exps.length];

			sequences[0].calc(len1, exps, ctx, values1);
			sequences[1].calc(1, exps, ctx, values2);
			if (Variant.compare(values1, values2, true) <= 0) {
				resultList.addAll(list1);
				resultList.addAll(list2);
				return result;
			}

			sequences[0].calc(1, exps, ctx, values1);
			sequences[1].calc(len2, exps, ctx, values2);
			if (Variant.compare(values2, values1, true) <= 0) {
				resultList.addAll(list2);
				resultList.addAll(list1);
				return result;
			}

			// �鲢��������
			sequences[1].calc(1, exps, ctx, values2);
			int s1 = 1, s2 = 1;
			while (true) {
				int cmp = Variant.compare(values1, values2, true);
				if (cmp == 0) {
					resultList.add(list1.get(s1));
					resultList.add(list2.get(s2));
					s1++;
					s2++;

					if (s1 > len1) break;
					if (s2 > len2) break;

					sequences[0].calc(s1, exps, ctx, values1);
					sequences[1].calc(s2, exps, ctx, values2);
				} else if (cmp > 0) {
					resultList.add(list2.get(s2));
					s2++;

					if (s2 > len2) break;
					sequences[1].calc(s2, exps, ctx, values2);
				} else {
					resultList.add(list1.get(s1));
					s1++;

					if (s1 > len1) break;
					sequences[0].calc(s1, exps, ctx, values1);
				}
			}

			// ��ʣ���Ԫ�����ӵ�������
			if (s1 <= len1) {
				resultList.addSection(list1, s1);
			} else {
				resultList.addSection(list2, s2);
			}
			
			return result;
		} else {
			// �������Ż���
			Object [][]itemVals = new Object[count][]; // ���еĵ�ǰԪ��
			int []items = new int[count]; // ���еĵ�ǰԪ�ش�С���������
			int []index = new int[count]; // ���еĵ�ǰλ��

			Next:
			for (int i = 0; i < count; ++i) {
				index[i] = 1;
				if (lens[i] == 0) {
					items[i] = -1;
					continue Next;
				}

				Object []values = new Object[exps.length];
				sequences[i].calc(1, exps, ctx, values);
				itemVals[i] = values;

				for (int j = 0; j < i; ++j) {
					if (items[j] == -1) {
						items[j] = i;
						items[i] = -1;
						continue Next;
					} else if (Variant.compare(values, itemVals[items[j]], true) < 0) {
						for (int k = i; k > j; --k) {
							items[k] = items[k - 1];
						}

						items[j] = i;
						continue Next;
					}
				}

				items[i] = i;
			}

			Sequence result = new Sequence(total);
			ListBase1 resultList = result.mems;

			Next:
			for(int i = 1; i <= total; ++i) {
				int item = items[0];
				resultList.add(lists[item].get(index[item]));
				index[item]++;
				if (index[item] <= lens[item]) {
					Object []values = itemVals[item];
					sequences[item].calc(index[item], exps, ctx, values);
					for (int j = 1; j < count; ++j) {
						if (items[j] == -1 || Variant.compare(values, itemVals[items[j]], true) <= 0) {
							items[j - 1] = item;
							continue Next;
						} else {
							items[j - 1] = items[j];
						}
					}

					items[count - 1] = item;
				} else {
					for (int j = 1; j < count; ++j) {
						items[j - 1] = items[j];
					}

					itemVals[item] = null;
					items[count - 1] = -1;
				}
			}

			return result;
		}
	}

	// ����ֵ�������ϲ�Դ���У�ֵ��������
	private static Sequence union(Sequence[] sources, Sequence[] values) {
		int count = values.length;
		int leaveCount = count;
		int[] len = new int[count];
		int[] index = new int[count];
		int totalLen = 0;

		ListBase1[] srcMems = new ListBase1[count];
		ListBase1[] valMems = new ListBase1[count];

		for (int i = 0; i < count; ++i) {
			valMems[i] = values[i].mems;
			srcMems[i] = sources[i].mems;

			index[i] = 1;
			len[i] = valMems[i].size();
			totalLen += len[i];
			if (len[i] == 0) {
				leaveCount--;
			}
		}

		Object minValue = null; ;
		int[] minIndex = new int[count];
		Sequence result = new Sequence(totalLen);
		ListBase1 resultMems = result.mems;

		while (leaveCount > 1) {
			for (int i = 0; i < count; ++i) {
				if (index[i] <= len[i]) {
					minValue = valMems[i].get(index[i]);
					minIndex[0] = i;
					break;
				}
			}

			int sameCount = 1;
			for (int i = minIndex[0] + 1; i < count; ++i) {
				if (index[i] <= len[i]) {
					Object value = valMems[i].get(index[i]);
					int cmp = Variant.compare(minValue, value, true);
					if (cmp > 0) {
						minValue = value;
						minIndex[0] = i;
						sameCount = 1;
					} else if (cmp == 0) {
						minIndex[sameCount] = i;
						sameCount++;
					} // < 0
				}
			}

			resultMems.add(srcMems[minIndex[0]].get(index[minIndex[0]]));
			for (int i = 0; i < sameCount; ++i) {
				index[minIndex[i]]++;
				if (index[minIndex[i]] > len[minIndex[i]]) {
					leaveCount--;
				}
			}
		}

		for (int i = 0; i < count; ++i) {
			if (index[i] <= len[i]) {
				for (int j = index[i]; j <= len[i]; ++j) {
					resultMems.add(srcMems[i].get(j));
				}
				
				break;
			}
		}

		return result;
	}

	// ����ֵ�������ϲ�Դ���У�ֵ��������
	private static Sequence isect(Sequence[] sources, Sequence[] values) {
		int count = values.length;
		int[] len = new int[count];
		int[] index = new int[count];

		ListBase1[] srcMems = new ListBase1[count];
		ListBase1[] valMems = new ListBase1[count];

		int minLen = Integer.MAX_VALUE;
		for (int i = 0; i < count; ++i) {
			valMems[i] = values[i].mems;
			srcMems[i] = sources[i].mems;

			index[i] = 1;
			len[i] = valMems[i].size();
			if (len[i] == 0) {
				return new Sequence(0);
			}

			if (len[i] < minLen) {
				minLen = len[i];
			}
		}

		Sequence result = new Sequence(minLen);
		ListBase1 resultMems = result.mems;

		Next:
		for (int col1 = 1; col1 <= len[0]; ++col1) {
			Object val1 = valMems[0].get(col1);

			NextCol:
			for (int i = 1; i < count; ++i) {
				for (; index[i] <= len[i]; ++index[i]) {
					Object value = valMems[i].get(index[i]);
					int cmp = Variant.compare(val1, value, true);
					if (cmp < 0) {
						continue Next;
					} else if (cmp == 0) {
						index[i]++;
						continue NextCol;
					} // > 0
				}
				
				break Next;
			}

			resultMems.add(srcMems[0].get(col1));
		}

		return result;
	}

	// ����ֵ�������ϲ�Դ���У�ֵ��������
	private static Sequence diff(Sequence[] sources, Sequence[] values) {
		int count = values.length;
		int[] len = new int[count];
		int[] index = new int[count];

		ListBase1[] srcMems = new ListBase1[count];
		ListBase1[] valMems = new ListBase1[count];

		for (int i = 0; i < count; ++i) {
			valMems[i] = values[i].mems;
			srcMems[i] = sources[i].mems;
			index[i] = 1;
			len[i] = valMems[i].size();
		}

		Sequence result = new Sequence(len[0]);
		ListBase1 resultMems = result.mems;

		for (int r = 1; r <= len[0]; ++r) {
			Object val1 = valMems[0].get(r);
			boolean find = false;
			for (int i = 1; i < count; ++i) {
				for (; index[i] <= len[i]; ++index[i]) {
					Object value = valMems[i].get(index[i]);
					int cmp = Variant.compare(val1, value, true);
					if (cmp < 0) {
						break;
					} else if (cmp == 0) {
						index[i]++;
						find = true;
						break;
					} // > 0
				}
			}

			if (!find) {
				resultMems.add(srcMems[0].get(r));
			}
		}

		return result;
	}

	/**
	 * �ϲ����е�Ԫ�أ�����Ԫ��Ϊ���У��Ұ�����ʽ����
	 * @param exps Expression[] ����ʽ���������������Ԫ��ֵ�������ϲ�
	 * @param opt String u���󲢣�i���󽻣�d����Ĭ��Ϊ����
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence merge(Expression[] exps, String opt, Context ctx) {
		boolean bUnion = false, bIsect = false, bDiff = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1)bUnion = true;
			if (opt.indexOf('i') != -1)bIsect = true;
			if (opt.indexOf('d') != -1)bDiff = true;
			
			if (opt.indexOf('o') != -1) {
				if (bUnion) {
					return union(exps, ctx);
				} else if (bIsect) {
					return isect(exps, ctx);
				} else if (bDiff) {
					return diff(exps, ctx);
				} else {
					return conj(null);
				}
			}
		}

		int count = count(null);
		int len = length();
		Sequence []sequences = new Sequence[count];
		for (int i = 1, seq = 0; i <= len; ++i) {
			Object obj = getMem(i);
			if (obj instanceof Sequence) {
				sequences[seq] = (Sequence)obj;
				seq++;
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("merge" + mm.getMessage("function.paramTypeError"));
			}
		}

		if (bUnion) {
			if (exps == null || exps.length == 0) {
				Sequence retSeq = sequences[0];
				for (int i = 1; i < count; ++i) {
					retSeq = retSeq.union(sequences[i], true);
				}

				return retSeq;
			} else {
				Sequence[] values = new Sequence[count];
				for (int i = 0; i < count; ++i) {
					values[i] = sequences[i].calc(exps, ctx);
				}

				return union(sequences, values);
			}
		} else if (bIsect) {
			if (count != len) return null;

			if (exps == null || exps.length == 0) {
				Sequence retSeq = sequences[0];
				for (int i = 1; i < count; ++i) {
					retSeq = retSeq.isect(sequences[i], true);
				}

				return retSeq;
			} else {
				Sequence[] values = new Sequence[count];
				for (int i = 0; i < count; ++i) {
					values[i] = sequences[i].calc(exps, ctx);
				}

				return isect(sequences, values);
			}
		} else if (bDiff) {
			// A(1)\(A(2)&...)
			if (getMem(1) == null) return null;
			if (count == 1) return sequences[0];
			
			if (exps == null || exps.length == 0) {
				Sequence seq = sequences[1];
				for (int i = 2; i < count; ++i) {
					seq = seq.union(sequences[i], true);
				}

				return sequences[0].diff(seq, true);
			} else {
				Sequence[] values = new Sequence[count];
				for (int i = 0; i < count; ++i) {
					values[i] = sequences[i].calc(exps, ctx);
				}

				return diff(sequences, values);
			}
		} else {
			return mergeConj(sequences, exps, ctx);
		}
	}

	/**
	 * �Ƚ��������Ƿ����
	 * @param seq Sequence
	 * @return boolean
	 */
	public boolean isEquals(Sequence seq) {
		if (seq == this) {
			return true;
		} else if (seq == null) {
			return false;
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		if (len != mems2.size()) {
			return false;
		}

		for (int i = 1; i <= len; ++i) {
			if (!Variant.isEquals(mems.get(i), mems2.get(i))) {
				return false;
			}
		}

		return true;
	}

	/**
	 * �Ƚ������еĴ�С
	 * @param seq Sequence
	 * @return 1����ǰ���д�0������������ȣ�-1����ǰ����С
	 */
	public int cmp(Sequence seq) {
		if (seq == this) {
			return 0;
		} else if (seq == null) {
			return 1;
		}


		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		int min = len > len2 ? len2 : len;

		for (int i = 1; i <= min; ++i) {
			int result = Variant.compare(mems.get(i), mems2.get(i), true);
			if (result != 0) {
				return result;
			} // ��ȱȽ���һ��
		}

		return len == len2 ? 0 : (len > len2 ? 1 : -1);
	}
	
	/**
	 * �Ƚ������еĴ�С��null�����ֵ����
	 * @param seq Sequence
	 * @return 1����ǰ���д�0������������ȣ�-1����ǰ����С
	 */
	public int cmp_0(Sequence seq) {
		if (seq == this) {
			return 0;
		} else if (seq == null) {
			return -1;
		}


		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		int min = len > len2 ? len2 : len;

		for (int i = 1; i <= min; ++i) {
			int result = Variant.compare_0(mems.get(i), mems2.get(i));
			if (result != 0) {
				return result;
			} // ��ȱȽ���һ��
		}

		return len == len2 ? 0 : (len > len2 ? 1 : -1);
	}

	/**
	 * ��ָ���Ƚ����Ƚ��������еĴ�С
	 * @param seq �뵱ǰ���н��бȽϵ�����
	 * @param comparator �Ƚ���
	 * @return 1����ǰ���д�0������������ȣ�-1����ǰ����С
	 */
	public int cmp(Sequence seq, Comparator<Object> comparator) {
		if (seq == this) {
			return 0;
		} else if (seq == null) {
			return 1;
		}

		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		int min = len > len2 ? len2 : len;

		for (int i = 1; i <= min; ++i) {
			int result = Variant.compare(mems.get(i), mems2.get(i), comparator, true);
			if (result != 0) {
				return result;
			} // ��ȱȽ���һ��
		}

		return len == len2 ? 0 : (len > len2 ? 1 : -1);
	}
	
	/**
	 * ��ָ���Ƚ����Ƚ��������еĴ�С��null�����ֵ����
	 * @param seq �뵱ǰ���н��бȽϵ�����
	 * @param comparator �Ƚ���
	 * @return 1����ǰ���д�0������������ȣ�-1����ǰ����С
	 */
	public int cmp_0(Sequence seq, Comparator<Object> comparator) {
		if (seq == this) {
			return 0;
		} else if (seq == null) {
			return -1;
		}


		ListBase1 mems = this.mems;
		ListBase1 mems2 = seq.mems;
		int len = mems.size();
		int len2 = mems2.size();
		int min = len > len2 ? len2 : len;

		for (int i = 1; i <= min; ++i) {
			int result = Variant.compare_0(mems.get(i), mems2.get(i), comparator);
			if (result != 0) {
				return result;
			} // ��ȱȽ���һ��
		}

		return len == len2 ? 0 : (len > len2 ? 1 : -1);
	}

	/**
	 * �ѵ�ǰ�������ԱֵΪ0��ͬ�ȳ������н��бȽ�
	 * @return 1�����ڣ�0�����ڣ�-1��С��
	 */
	public int cmp0() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return -1;
		}

		Integer zero = new Integer(0);
		for (int i = 1; i <= size; ++i) {
			int result = Variant.compare(mems.get(i), zero, true);
			if (result != 0) {
				return result;
			} // ��ȱȽ���һ��
		}
		
		return 0;
	}

	/**
	 * �������еĳ�Ա��
	 * @return true����Ա�����棬false�����ڳ�ԱȡֵΪ��
	 */
	public boolean cand() {
		ListBase1 mems = this.mems;
		for (int i = 1, len = mems.size(); i <= len; ++i) {
			if (Variant.isFalse(mems.get(i))) {
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * ������м������ʽ�����ؼ���������
	 * @param exp ����ʽ
	 * @param ctx ����������
	 * @return true������ʽ�����������棬false�����ڱ���ʽ������Ϊ��
	 */
	public boolean cand(Expression exp, Context ctx) {
		ListBase1 mems = this.mems;
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1, len = mems.size(); i <= len; ++i) {
				current.setCurrent(i);
				if (Variant.isFalse(exp.calculate(ctx))) {
					return false;
				}
			}
		} finally {
			stack.pop();
		}
		
		return true;
	}
	
	public boolean cor() {
		ListBase1 mems = this.mems;
		for (int i = 1, len = mems.size(); i <= len; ++i) {
			if (Variant.isTrue(mems.get(i))) {
				return true;
			}
		}
		
		return false;
	}
	
	public boolean cor(Expression exp, Context ctx) {
		ListBase1 mems = this.mems;
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1, len = mems.size(); i <= len; ++i) {
				current.setCurrent(i);
				if (Variant.isTrue(exp.calculate(ctx))) {
					return true;
				}
			}
		} finally {
			stack.pop();
		}
		
		return false;
	}

	/**
	 * ���ص�һ����Ϊnull��Ԫ��
	 * @return Object
	 */
	public Object ifn() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				return obj;
			}
		}
		return null;
	}
	
	/**
	 * ���ص�һ����Ϊnull���Ҳ���""��Ԫ��
	 * @return Object
	 */
	public Object nvl() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null && !obj.equals("")) {
				return obj;
			}
		}
		
		return null;
	}

	/**
	 * ���ص�һ����Ϊnull�ı���ʽ����ֵ
	 * @param exp Expression �������ʽ
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Object ifn(Expression exp, Context ctx) {
		if (exp == null) {
			return ifn();
		}

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			int size = mems.size();
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				Object obj = exp.calculate(ctx);
				if (obj != null) {
					return obj;
				}
			}
		} finally {
			stack.pop();
		}
		
		return null;
	}
	
	/**
	 * ���ص�һ����Ϊnull�ı���ʽ����ֵ
	 * @param exp Expression �������ʽ
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Object nvl(Expression exp, Context ctx) {
		if (exp == null) {
			return nvl();
		}

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			int size = mems.size();
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				Object obj = exp.calculate(ctx);
				if (obj != null && !obj.equals("")) {
					return obj;
				}
			}
		} finally {
			stack.pop();
		}
		
		return null;
	}

	/**
	 * �������еķ��ظ�Ԫ������������null
	 * @param opt o����������
	 * @return
	 */
	public int icount(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (opt == null || opt.indexOf('o') == -1) {
			HashSet<Object> set = new HashSet<Object>(size);
			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (Variant.isTrue(obj)) {
					set.add(obj);
				}
			}
			
			return set.size();
		} else {
			int count = 0;
			Object prev = null;

			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (Variant.isTrue(obj) && !Variant.isEquals(prev, obj)) {
					prev = obj;
					count++;
				}
			}

			return count;
		}
	}
	
	/**
	 * ����ȥ���ظ���Ԫ�غ������
	 * @param opt String o��ֻ�����ڵĶԱȣ�u�������������h������������@o����
	 * @return Sequence
	 */
	public Sequence id(String opt) {
		if (opt == null) {
			if (length() > SORT_HASH_LEN) {
				return CursorUtil.hashId(this, opt);
			} else {
				return sort(null).id("o");
			}
		} else if (opt.indexOf('h') != -1) {
			return sort(null).id("o");
		} else if (opt.indexOf('o') != -1) {
			ListBase1 mems = this.mems;
			int size = mems.size();
			Sequence result = new Sequence(size);
			if (size == 0) {
				return result;
			}
			
			ListBase1 resultMems = result.mems;
			Object prev = mems.get(1);
			resultMems.add(prev);

			for (int i = 2; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!Variant.isEquals(prev, obj)) {
					prev = obj;
					resultMems.add(obj);
				}
			}

			return result;
		} else if (opt.indexOf('u') != -1) {
			return CursorUtil.hashId(this, opt);
		} else {
			if (length() > SORT_HASH_LEN) {
				return CursorUtil.hashId(this, opt);
			} else {
				return sort(null).id("o");
			}
		}
	}

	/**
	 * ���������г��ִ������ĳ�Ա
	 * @return
	 */
	public Object mode() {
		ListBase1 mems = this.mems;
		int len = mems.size();
		HashMap<Object, Integer> map = new HashMap<Object, Integer>(len);
		
		for (int i = 1; i <= len; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				Integer n = map.get(obj);
				if (n == null) {
					map.put(obj, 1);
				} else {
					map.put(obj, n + 1);
				}
			}
		}
		
		Object result = null;
		int count = 0;
		Set<Map.Entry<Object, Integer>> entrySet = map.entrySet();
		Iterator<Map.Entry<Object, Integer>> itr = entrySet.iterator();
		while (itr.hasNext()) {
			Map.Entry<Object, Integer> entry = itr.next();
			if (entry.getValue() > count) {
				result = entry.getKey();
				count = entry.getValue();
			}
		}
		
		return result;
	}
	
	/**
	 * ��������Ԫ�صĺ�
	 * @return Object
	 */
	public Object sum() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		Number result = null;
		int i = 1;

		for (; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Number) {
				result = (Number)obj;
				break;
			}
		}

		for (++i; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Number) {
				result = Variant.addNum(result, (Number)obj);
			}
		}

		return result;
	}

	/**
	 * ��������Ԫ�ص�ƽ����
	 * @return Object
	 */
	public Object sum2() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		Object result = Variant.square(mems.get(1));
		for (int i = 2; i <= size; ++i) {
			result = Variant.add(result, Variant.square(mems.get(i)));
		}
		return result;
	}

	/**
	 * ����Ԫ����ƽ��ֵ֮��Ĳ�ֵ��ƽ���͵�ƽ��ֵ
	 * @return Object
	 */
	public Object variance() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		int count = size;
		Object sum = null;
		Object sum2 = null;
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				sum = Variant.add(sum, obj);
				sum2 = Variant.add(sum2, Variant.square(obj));
			} else {
				count--;
			}
		}

		if (count == 0)return null;

		Object countObj = new Integer(count);
		Object avg = Variant.divide(sum, countObj);

		// count*avg*avg + sum2 - 2*avg*sum
		Object result = Variant.square(avg);
		result = Variant.multiply(countObj, result);
		result = Variant.add(result, sum2);

		Object avgSum2 = Variant.multiply(avg, sum);
		avgSum2 = Variant.multiply(avgSum2, new Integer(2));

		result = Variant.subtract(result, avgSum2);
		return Variant.divide(result, countObj);
	}

	/**
	 * ����ƽ��ֵ��Ԫ�����ͱ���Ϊ��ֵ����ֵ�����м���
	 * @return Object
	 */
	public Object average() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		Number result = null;
		int count = 0;
		int i = 1;

		for (; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Number) {
				count++;
				result = (Number)obj;
				break;
			}
		}

		for (++i; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Number) {
				count++;
				result = Variant.addNum(result, (Number)obj);
			}
		}

		return Variant.avg(result, count);
	}

	/**
	 * ������Сֵ�����Կ�ֵ
	 * @return Object
	 */
	public Object min() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		Object minVal = null;
		int i = 1;
		for (; i <= size; ++i) {
			minVal = mems.get(i);
			if (minVal != null)break;
		}

		for (i = i + 1; i <= size; ++i) {
			Object temp = mems.get(i);
			if (temp != null && Variant.compare(temp, minVal, true) < 0) {
				minVal = temp;
			}
		}

		return minVal;
	}

	/**
	 * �������ֵ
	 * @return Object
	 */
	public Object max() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return null;
		}

		Object maxVal = mems.get(1);
		for (int i = 2; i <= size; ++i) {
			Object obj = mems.get(i);
			if (Variant.compare(maxVal, obj, true) < 0) {
				maxVal = obj;
			}
		}

		return maxVal;
	}

	/**
	 * ������С��count��Ԫ��
	 * @param count ����
	 * @return
	 */
	public Sequence min(int count) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) return null;

		MinHeap heap = new MinHeap(count);
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				heap.insert(obj);
			}
		}

		Object []objs = heap.toArray();
		Sequence sequence = new Sequence(objs);
		return sequence.sort("o");
	}

	/**
	 * ��������count��Ԫ��
	 * @param count ����
	 * @return
	 */
	public Sequence max(int count) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) return null;

		MaxHeap heap = new MaxHeap(count);
		for (int i = 1; i <= size; ++i) {
			heap.insert(mems.get(i));
		}

		Object []objs = heap.toArray();
		Sequence sequence = new Sequence(objs);
		return sequence.sort("zo");
	}
	
	// 0λ�ò��ã�ѡ������������С��Ԫ��
	private IntArrayList top1Index(boolean isMin, Expression exp, Context ctx) {
		if (exp == null) {
			ListBase1 mems = this.mems;
			int end = mems.size();
			IntArrayList list = new IntArrayList();
			list.addInt(0);
			
			int i = 1;
			Object prevValue = null;
			for (; i <= end; ++i) {
				prevValue = mems.get(i);
				if (prevValue != null) { // ���Կ�ֵ
					list.addInt(i);
					break;
				}
			}

			if (isMin) {
				for (++i; i <= end; ++i) {
					Object temp = mems.get(i);
					if (temp != null) {
						int result = Variant.compare(temp, prevValue, true);
						if (result < 0) {
							prevValue = temp;
							list.setSize(1);
							list.addInt(i);
						} else if (result == 0) {
							list.addInt(i);
						} // ���ڲ����κδ���
					}
				}
			} else {
				for (++i; i <= end; ++i) {
					Object temp = mems.get(i);
					if (temp != null) {
						int result = Variant.compare(temp, prevValue, true);
						if (result > 0) {
							prevValue = temp;
							list.setSize(1);							
							list.addInt(i);
						} else if (result == 0) {
							list.addInt(i);
						} // ���ڲ����κδ���
					}
				}
			}
			
			return list;
		} else if (exp.isConstExpression()) {
			int len = length();
			if (isMin) {
				IntArrayList list = new IntArrayList(2);
				list.addInt(0);
				list.addInt(1);
				return list;
			} else {
				IntArrayList list = new IntArrayList(2);
				list.addInt(0);
				if (len != 0) {
					list.add(len);
				}
				
				return list;
			}
		} else {
			Sequence values = calc(exp, ctx);
			return values.top1Index(isMin, null, ctx);
		}
	}
	
	// ����ʹ����ʽ����ķ���ֵ��С������󣩵�ȫ��Ԫ�ص�λ��
	private IntArrayList top1Index(Expression []exps, boolean isMin, Context ctx) {
		int end = length();
		int fcount = exps.length;
		IntArrayList list = new IntArrayList();
		list.addInt(0);
		
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			Object []prevValues = new Object[fcount];
			current.setCurrent(1);
			for (int f = 0; f < fcount; ++f) {
				prevValues[f] = exps[f].calculate(ctx);
			}
			
			list.addInt(1);
			if (isMin) {
				Next:
				for (int i = 2; i <= end; ++i) {
					current.setCurrent(i);
					for (int f = 0; f < fcount; ++f) {
						Object obj = exps[f].calculate(ctx);
						int result = Variant.compare(obj, prevValues[f], true);
						if (result < 0) {
							// �����µ���Сֵ
							prevValues[f] = obj;
							for (++f; f < fcount; ++f) {
								prevValues[f] = exps[f].calculate(ctx);
							}
							
							list.setSize(1);
							list.addInt(i);
							continue Next;
						} else if (result > 0) {
							continue Next;
						}
					}
					
					// �����ֶζ���ǰ�����Сֵ���
					list.addInt(i);
				}
			} else {
				Next:
				for (int i = 2; i <= end; ++i) {
					current.setCurrent(i);
					for (int f = 0; f < fcount; ++f) {
						Object obj = exps[f].calculate(ctx);
						int result = Variant.compare(obj, prevValues[f], true);
						if (result > 0) {
							// �����µ����ֵ
							prevValues[f] = obj;
							for (++f; f < fcount; ++f) {
								prevValues[f] = exps[f].calculate(ctx);
							}
							
							list.setSize(1);
							list.addInt(i);
							continue Next;
						} else if (result < 0) {
							continue Next;
						}
					}
					
					// �����ֶζ���ǰ�����Сֵ���
					list.addInt(i);
				}
			}
		} finally {
			stack.pop();
		}
		
		return list;
	}

	// 0λ�ò���
	private IntArrayList topIndex(int count, Expression exp, Context ctx) {
		if (exp == null) {
			if (count > 0) {
				return pmin(count, new BaseComparator());
			} else {
				return pmin(-count, new DescComparator());
			}
		} else if (exp.isConstExpression()) {
			int len = length();
			if (count > 0) {
				if (count > len) count = len;
				
				IntArrayList list = new IntArrayList(count + 1);
				list.addInt(0);
				for (int i = 1; i <= count; ++i) {
					list.addInt(i);
				}
				
				return list;
			} else {
				int i = len + count + 1;
				if (i < 1) i = 1;
				
				IntArrayList list = new IntArrayList(-count + 1);
				list.addInt(0);
				for (; i <= len; ++i) {
					list.addInt(i);
				}
				
				return list;
			}
		} else {
			Sequence values = calc(exp, ctx);
			if (count > 0) {
				return values.pmin(count, new BaseComparator());
			} else {
				return values.pmin(-count, new DescComparator());
			}
		}
	}

	/**
	 * �����а�����ʽ��������ȡǰcount����λ��
	 * @param count ����
	 * @param exp �������ʽ
	 * @param opt 1������λ�ã�Ĭ�Ϸ���λ������
	 * @param ctx ����������
	 * @return λ�û�λ������
	 */
	public Object ptop(int count, Expression exp, String opt, Context ctx) {
		if (count == 0) {
			return null;
		}
		
		IntArrayList indexList;
		if ((count == 1 || count == -1) && (opt == null || opt.indexOf('1') == -1)) {
			indexList = top1Index(count == 1, exp, ctx);
		} else {
			indexList = topIndex(count, exp, ctx);
		}

		int size = indexList.size() - 1;
		if (size == 0) return null;

		if (size == 1 && opt != null && opt.indexOf('1') != -1) {
			return indexList.get(1);
		}
		
		Sequence result = new Sequence(size);
		for (int i = 1; i <= size; ++i) {
			result.add(indexList.get(i));
		}

		return result;
	}
	
	/**
	 * ȡexpsǰ��λ�ļ�¼��λ��
	 * @param count ����
	 * @param exps ����ʽ���飬���ڱȽϴ�С
	 * @param opt ѡ��
	 * @param ctx
	 * @param getPos true��ȡλ�ã�false��ȡ��¼
	 * @return
	 */
	public Object top(int count, Expression []exps, String opt, Context ctx, boolean getPos) {
		int len = length();
		if (len == 0 || count == 0) {
			return null;
		}
		
		// ȡ������С������
		if ((count == 1 || count == -1) && (opt == null || opt.indexOf('1') == -1)) {
			IntArrayList indexList = top1Index(exps, count == 1, ctx);
			int size = indexList.size() - 1;
			Sequence result = new Sequence(size);
			
			if (getPos) {
				for (int i = 1; i <= size; ++i) {
					result.add(indexList.get(i));
				}
			} else {
				for (int i = 1; i <= size; ++i) {
					result.add(getMem(indexList.getInt(i)));
				}
			}
	
			return result;
		}
		
		int expCount = exps.length;
		int arrayLen = expCount + 1;
		Comparator<Object> comparator = new ArrayComparator(expCount);
		if (count < 0) {
			count = -count;
			comparator = new DescComparator(comparator);
		}
		
		MinHeap minHeap = new MinHeap(count, comparator);
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object []vals = new Object[arrayLen];
				vals[expCount] = i;
				for (int j = 0; j < expCount; ++j) {
					vals[j] = exps[j].calculate(ctx);
				}
				
				minHeap.insert(vals);
			}
		} finally {
			stack.pop();
		}
		
		if (count == 1 && opt != null && opt.indexOf('1') != -1) {
			Object []vals = (Object [])minHeap.getTop();
			if (getPos) {
				return vals[expCount];
			} else {
				return getMem((Integer)vals[expCount]);
			}
		}
		
		int size = minHeap.size();
		Object []vals = minHeap.toArray();
		Arrays.sort(vals, comparator);
		
		Sequence result = new Sequence(size);
		if (getPos) {
			for (int i = 0; i < size; ++i) {
				Object []curVals = (Object [])vals[i];
				result.add(curVals[expCount]);
			}
		} else {
			for (int i = 0; i < size; ++i) {
				Object []curVals = (Object [])vals[i];
				result.add(getMem((Integer)curVals[expCount]));
			}
		}
		
		return result;		
	}
	
	/**
	 * ȡcount��exp����ֵ����Сֵ
	 * @param count ����
	 * @param exp �������ʽ
	 * @param opt ѡ�� 1����countΪ����1ʱ��ֻȡһ����Ĭ��ȡ������ͬ��
	 * @param ctx ����������
	 * @return Object
	 */
	public Object top(int count, Expression exp, String opt, Context ctx) {
		if (count == 0) {
			return null;
		}
		
		Sequence seq = calc(exp, ctx);
		int len = seq.length();
		ListBase1 mems = seq.mems;
		
		if (opt != null && opt.indexOf('2') != -1) {
			for (int i = 1; i <= len; ++i) {
				if (mems.get(i) instanceof Sequence) {
					seq = seq.conj(null);
					mems = seq.mems;
					break;
				}
			}
		}

		IntArrayList indexList;
		if ((count == 1 || count == -1) && (opt == null || opt.indexOf('1') == -1)) {
			indexList = seq.top1Index(count == 1, null, ctx);
		} else {
			indexList = seq.topIndex(count, null, ctx);
		}
		
		int size = indexList.size() - 1;
		if (size == 0) {
			return null;
		} else if (size == 1 && opt != null && opt.indexOf('1') != -1) {
			return mems.get(indexList.getInt(1));
		} else {
			Sequence result = new Sequence(size);
			for (int i = 1; i <= size; ++i) {
				result.add(mems.get(indexList.getInt(i)));
			}
	
			return result;
		}
	}
	
	/**
	 * ȡcount��ʹexp����ֵ��С��Ԫ�ص�getExp����ֵ
	 * @param count ����
	 * @param exp �Ƚϱ���ʽ
	 * @param getExp ����ֵ����ʽ
	 * @param opt ѡ�� 1����countΪ����1ʱ��ֻȡһ����Ĭ��ȡ������ͬ��
	 * @param ctx ����������
	 * @return Object
	 */
	public Object top(int count, Expression exp, Expression getExp, String opt, Context ctx) {
		if (count == 0) return null;
		
		Sequence seq = calc(getExp, ctx);
		int len = seq.length();
		ListBase1 mems = seq.mems;
		
		if (opt != null && opt.indexOf('2') != -1) {
			for (int i = 1; i <= len; ++i) {
				if (mems.get(i) instanceof Sequence) {
					seq = seq.conj(null);
					mems = seq.mems;
					break;
				}
			}
		}
		
		IntArrayList indexList;
		if ((count == 1 || count == -1) && (opt == null || opt.indexOf('1') == -1)) {
			indexList = seq.top1Index(count == 1, exp, ctx);
		} else {
			indexList = seq.topIndex(count, exp, ctx);
		}
		
		int size = indexList.size() - 1;
		if (size == 0) {
			return null;
		} else if (size == 1 && opt != null && opt.indexOf('1') != -1) {
			return mems.get(indexList.getInt(1));
		} else {
			Sequence result = new Sequence(size);
			for (int i = 1; i <= size; ++i) {
				result.add(mems.get(indexList.getInt(i)));
			}
	
			return result;
		}
	}

	// 0λ�ò���
	private IntArrayList pmin(int count, Comparator<Object> comparator) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		IntArrayList indexList = new IntArrayList(count + 1);
		indexList.addInt(0);

		if (count == 1) {
			Object minValue = null;
			int p = 1;
			for (; p <= size; ++p) {
				minValue = mems.get(p);
				if (minValue != null) { // ���Կ�ֵ
					break;
				}
			}

			for (int i = p + 1; i <= size; ++i) {
				Object temp = mems.get(i);
				if (temp != null && comparator.compare(temp, minValue) < 0) {
					minValue = temp;
					p = i;
				}
			}

			if (minValue != null) {
				indexList.addInt(p);
			}
		} else {
			ListBase1 values = new ListBase1(count);
			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (obj != null) {
					int index = values.binarySearch(obj, comparator);
					if (index < 1) index = -index;
					if (index <= count) {
						values.add(index, obj);
						indexList.addInt(index, i);
						if (values.size() > count) {
							values.remove(count + 1);
							indexList.remove(count + 1);
						}
					}
				}
			}
		}

		return indexList;
	}

	/**
	 * ������ʹָ������ʽֵ��С��count��Ԫ�ع��ɵ�����
	 * @param exp Expression �������ʽ
	 * @param count ����
	 * @param ctx Context ����������
	 * @return
	 */
	public Sequence minp(Expression exp, int count, Context ctx) {
		ListBase1 mems = this.mems;
		if (mems.size() < 1) return null;

		Sequence valSequence = calc(exp, ctx);
		IntArrayList indexList = valSequence.pmin(count, new BaseComparator());

		count = indexList.size() - 1;
		Sequence result = new Sequence(count);
		for (int i = 1; i <= count; ++i) {
			int index = indexList.getInt(i);
			result.add(mems.get(index));
		}

		return result;
	}

	/**
	 * ������ʹָ������ʽֵ����count��Ԫ�ع��ɵ�����
	 * @param exp Expression �������ʽ
	 * @param count ����
	 * @param ctx Context ����������
	 * @return
	 */
	public Sequence maxp(Expression exp, int count, Context ctx) {
		ListBase1 mems = this.mems;
		if (mems.size() < 1) return null;

		Sequence valSequence = calc(exp, ctx);
		IntArrayList indexList = valSequence.pmin(count, new DescComparator());

		count = indexList.size() - 1;
		Sequence result = new Sequence(count);
		for (int i = 1; i <= count; ++i) {
			int index = indexList.getInt(i);
			result.add(mems.get(index));
		}

		return result;
	}

	/**
	 * ����obj�������е�����
	 * @param obj Object
	 * @param opt String z������������i���ظ�����һ����s��ͳ��ѧ����������Double
	 * @return Number
	 */
	public Number rank(Object obj, String opt) {
		boolean isDesc = false, isDistinct = false, isStatistics = false;
		if (opt != null) {
			if (opt.indexOf('z') != -1)isDesc = true;
			if (opt.indexOf('i') != -1)isDistinct = true;
			if (opt.indexOf('s') != -1)isStatistics = true;
		}

		ListBase1 mems = this.mems;
		int length = mems.size();
		int count = 1;

		if (isDistinct) {
			Object[] objs = mems.toArray();
			if (isDesc) {
				MultithreadUtil.sort(objs, new DescComparator());
				for (int i = 0; i < length; ++i) {
					if (Variant.compare(objs[i], obj, true) > 0) {
						if (i == 0 || !Variant.isEquals(objs[i-1], objs[i])) {
							count++;
						}
					} else {
						break;
					}
				}
			} else {
				MultithreadUtil.sort(objs, new BaseComparator());
				for (int i = 0; i < length; ++i) {
					if (Variant.compare(objs[i], obj, true) < 0) {
						if (i == 0 || !Variant.isEquals(objs[i-1], objs[i])) {
							count++;
						}
					} else {
						break;
					}
				}
			}
		} else {
			if (isStatistics) {
				int sameCount = 0;
				if (isDesc) {
					for (int i = 1; i <= length; ++i) {
						int cmp = Variant.compare(mems.get(i), obj, true);
						if (cmp > 0) {
							count++;
						} else if (cmp == 0) {
							sameCount++;
						}
					}
				} else {
					for (int i = 1; i <= length; ++i) {
						int cmp = Variant.compare(mems.get(i), obj, true);
						if (cmp < 0) {
							count++;
						} else if (cmp == 0) {
							sameCount++;
						}
					}
				}
				
				if (sameCount > 1) {
					//double r = (i - 1) * sameCount;
					//r += sameCount * (1 + sameCount) / 2;
					//r /= sameCount;
					double r = (1.0 + sameCount) / 2 + count - 1;
					return new Double(r);
				} else {
					return new Double(count);
				}
			} else {
				if (isDesc) {
					for (int i = 1; i <= length; ++i) {
						if (Variant.compare(mems.get(i), obj, true) > 0) {
							count++;
						}
					}
				} else {
					for (int i = 1; i <= length; ++i) {
						if (Variant.compare(mems.get(i), obj, true) < 0) {
							count++;
						}
					}
				}
			}
		}

		return new Integer(count);
	}

	/**
	 * ����ÿ��Ԫ�ص�����
	 * @param opt String z������������i���ظ�����һ��
	 * @return Sequence
	 */
	public Sequence ranks(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0)return new Sequence(0);

		boolean isDesc = false, isDistinct = false, isStatistics = false;
		if (opt != null) {
			if (opt.indexOf('z') != -1)isDesc = true;
			if (opt.indexOf('i') != -1)isDistinct = true;
			if (opt.indexOf('s') != -1)isStatistics = true;
		}

		int len = size + 1;
		PSortItem[] infos = new PSortItem[len];
		for (int i = 1; i < len; ++i) {
			infos[i] = new PSortItem(i, mems.get(i));
		}

		Comparator<Object> comparator = new BaseComparator();
		if (isDesc) {
			comparator = new DescComparator(comparator);
		}

		// ��������
		MultithreadUtil.sort(infos, 1, len, new PSortComparator(comparator));

		Number[] places = new Number[size];
		if (isDistinct) {
			places[infos[1].index - 1] = new Integer(1);
			int count = 1;
			Object prev = mems.get(infos[1].index);
			for (int i = 2; i < len; ++i) {
				Object cur = mems.get(infos[i].index);
				if (!Variant.isEquals(prev, cur)) {
					count++;
					prev = cur;
				}

				places[infos[i].index - 1] = new Integer(count);
			}
		} else {
			if (isStatistics) {
				for (int i = 1; i < len;) {
					Object val = mems.get(infos[i].index);
					int sameCount = 1;
					for (int j = i + 1; j < len; ++j) {
						if (Variant.isEquals(val, mems.get(infos[j].index))) {
							sameCount++;
						} else {
							break;
						}
					}
					
					if (sameCount > 1) {
						//double r = (i - 1) * sameCount;
						//r += sameCount * (1 + sameCount) / 2;
						//r /= sameCount;
						double r = (1.0 + sameCount) / 2 + i - 1;
						Double d = new Double(r);
						
						for (int j = 0; j < sameCount; ++j) {
							places[infos[i + j].index - 1] = d;
						}
					} else {
						places[infos[i].index - 1] = new Double(i);
					}
					
					i += sameCount;
				}
			} else {
				places[infos[1].index - 1] = new Integer(1);
				for (int i = 2; i < len; ++i) {
					if (Variant.isEquals(mems.get(infos[i - 1].index),
										 mems.get(infos[i].index))) {
						places[infos[i].index - 1] = places[infos[i - 1].index - 1];
					} else {
						places[infos[i].index - 1] = new Integer(i);
					}
				}
			}
		}

		return new Sequence(places);
	}
	
	private void addAll_r(Object obj) {
		if (obj instanceof Sequence) {
			Sequence seq = (Sequence)obj;
			for (int i = 1, len = seq.length(); i <= len; ++i) {
				addAll_r(seq.getMem(i));
			}
		} else if (obj != null) {
			mems.add(obj);
		}
	}
	
	/**
	 * ��������ĳһ�ε�medianֵ
	 * ���øú���ʱ���������Ѿ�����
	 * �ú������ᱻ�û�ֱ�ӵ��ã��ò����Ϸ����ɵ����߱�֤��
	 * @param start	�ֶο�ʼλ��(�����ڷֶ���)
	 * @param end	�ֶν���λ��(�����ڷֶ���)
	 * @param	k	���ص�k�εĵ�һ�����ݡ�
	 * 				k����0ʱ���������ж�ͷ���ݵ����С�
	 * 				kС�ڵ���n
	 * @param	n	���������ݷֳɶ��ٶ�
	 * 				n����0��ȡ��ֵ��
	 * 				n����2����ʾ�ѱ������ݷֳ�n�Ρ�
	 * 				n������1
	 * @return	����median�Ľ��
	 */
	public Object median(int start, int end, int k, int n) {
		Sequence resSeq = null;
		
		// ��ʼ������
		if (2 <= n && 0 == k)
			resSeq = new Sequence();
		else if (0 == n && 0 == k) {
			n = 1;
			k = 2;
		}
		
		int len = end - start + 1;
		try {
			if (null == resSeq) {	// ������ֵ
				if (start+(len*k)/n-1 >= length()) {
					return get(length());
				} else if ((len*k)%n != 0)
					return get(start + len*k/n);
				else {
					return Variant.divide(Variant.add(get(start + len*k/n - 1),
							get(start + len*k/n)),
							2);
				}
			} else {	// ��Ҫ�������е����
				for (int i = 1; i < n; i++) {
					if (start+(len*i)/n-1 >= length()) {
						resSeq.add(get(length()));
					} else if ((len*i)%n != 0)
						resSeq.add(get(start + len*i/n));
					else {
						Object obj = Variant.divide(Variant.add(get(start + len*i/n - 1),
								get(start + len*i/n)),
								2);
						resSeq.add(obj);
					}
				}
	
				return resSeq;
			}
		} catch (Exception e) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("median" + mm.getMessage("function.invalidParam"));
		}
	}

	/**
	 * ���������е�Ԫ�ع��ɵĺ���
	 * @param opt String 'm': ����Ԫ��ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence conj(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}
		
		if (opt != null) {
			if (opt.indexOf('m') != -1) {
				// ���ù鲢���ϲ�Ԫ������
				Object obj = mems.get(1);
				Sequence sequence;
				if (obj instanceof Sequence) {
					sequence = (Sequence)obj;
				} else if (obj != null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needSeriesMember"));
				} else {
					sequence = new Sequence(0);
				}

				for (int i = 2; i <= size; ++i) {
					obj = mems.get(i);
					if (obj instanceof Sequence) {
						sequence = sequence.conj((Sequence)obj, true);
					} else if (obj != null) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.needSeriesMember"));
					}
				}
				
				return sequence;
			} else if (opt.indexOf('r') != -1) {
				int len = length();
				Sequence result = new Sequence(len + 8);
				for (int i = 1; i <= len; ++i) {
					result.addAll_r(getMem(i));
				}
				
				return result;
			}
		}

		// ����������һ���ж���Ԫ��
		int total = 0;
		boolean hasSeq = false;
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Sequence) {
				total += ((Sequence)obj).length();
				hasSeq = true;
			} else if (obj != null) {
				total += 1;
			}
		}
		
		if (total == 0) {
			return new Sequence();
		} else if (!hasSeq && total == size) {
			return this;
		}

		Sequence result = new Sequence(total);
		ListBase1 resultMems = result.mems;
		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Sequence) {
				resultMems.addAll(((Sequence)obj).mems);
			} else if (obj != null) {
				resultMems.add(obj);
			}
		}

		return result;
	}
	
	// �ݹ�ϲ��ֶ�ֵ
	public Sequence fieldValues_r(String field) {
		ListBase1 mems = this.mems;
		int size = mems.size;
		Sequence result = new Sequence(size);
		DataStruct prevDs = null;
		int col = -1;
		
		Next:
		for (int i = 1; i <= size; ++i) {
			Object cur = mems.get(i);
			while (cur instanceof Record) {
				Record r = (Record)cur;
				DataStruct ds = r.dataStruct();
				if (prevDs != ds) {
					prevDs = ds;
					col = r.getFieldIndex(field);
				}
				
				if (col == -1) {
					result.add(r);
					continue Next;
				} else {
					cur = r.getNormalFieldValue(col);
				}
			}
			
			if (cur instanceof Sequence) {
				Sequence seq = ((Sequence)cur).fieldValues_r(field);
				result.add(seq);
			} else {
				result.add(cur);
			}
		}
		
		return result;
	}
	
	// �ݹ�ϲ��ֶ�ֵ
	public Sequence fieldValues_r(int col) {
		ListBase1 mems = this.mems;
		int size = mems.size;
		Sequence result = new Sequence(size);		
		for (int i = 1; i <= size; ++i) {
			Object cur = mems.get(i);
			if (cur instanceof Record) {
				Record r = (Record)cur;
				cur = r.getNormalFieldValue(col);
			}
			
			if (cur instanceof Sequence) {
				Sequence seq = ((Sequence)cur).fieldValues_r(col);
				result.add(seq);
			} else if (cur instanceof Record) {
				Sequence seq = new Sequence(1);
				seq.add(cur);
				seq = seq.fieldValues_r(col);
				result.addAll(seq);
			} else {
				result.add(cur);
			}
		}
		
		return result;
	}

	/**
	 * ����ָ������ʽ�������ĺ���
	 * @param exp ����ʽ
	 * @param ctx ����������
	 * @return Sequence
	 */
	public Sequence conj(Expression exp, Context ctx) {
		int len = length();
		Sequence result = new Sequence(len);
		ListBase1 resultMems = result.mems;

		if (exp != null) {
			ComputeStack stack = ctx.getComputeStack();
			Current current = new Current();
			stack.push(current);

			try {
				for (int i = 1; i <= len; ++i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (obj instanceof Sequence) {
						resultMems.addAll(((Sequence)obj).mems);
					} else if (obj != null) {
						resultMems.add(obj);
					}
				}
			} finally {
				stack.pop();
			}
		} else {
			ListBase1 mems = this.mems;
			for (int i = 1; i <= len; ++i) {
				Object obj = mems.get(i);
				if (obj instanceof Sequence) {
					resultMems.addAll(((Sequence)obj).mems);
				} else if (obj != null) {
					resultMems.add(obj);
				}
			}
		}

		return result;
	}
	
	/**
	 * ���������е�Ԫ�ع��ɵĲ���
	 * @param opt String 'm': ����Ԫ��ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence diff(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		boolean bMerge = (opt != null && opt.indexOf('m') != -1);
		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			return new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = result.diff((Sequence)obj, bMerge);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			}
		}
		
		return result;
	}
	
	public Sequence diff(Expression []exps, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			return new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = CursorUtil.diff(result, (Sequence)obj, exps, ctx);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			}
		}
		
		return result;
	}

	/**
	 * ���������е�Ԫ�ع��ɵĽ���
	 * @param opt String 'm': ����Ԫ��ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence isect(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		boolean bMerge = (opt != null && opt.indexOf('m') != -1);
		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			return new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = result.isect((Sequence)obj, bMerge);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			} else {
				return new Sequence(0);
			}
		}
		
		return result;
	}

	/**
	 * �����г�Ա��ָ������ʽ�����������еĳ�Աͨ�������У�
	 * @param exps ����ʽ����
	 * @param ctx ����������
	 * @return ����
	 */
	public Sequence isect(Expression []exps, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			return new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = CursorUtil.isect(result, (Sequence)obj, exps, ctx);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			} else {
				return new Sequence(0);
			}
		}
		
		return result;
	}

	/**
	 * ���������е�Ԫ�ع��ɵĲ���
	 * @param opt String 'm': ����Ԫ��ͬ���ù鲢�㷨ʵ��
	 * @return Sequence
	 */
	public Sequence union(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		boolean bMerge = (opt != null && opt.indexOf('m') != -1);
		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			result = new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = result.union((Sequence)obj, bMerge);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			}
		}
		
		return result;
	}
	
	public Sequence union(Expression []exps, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			result = new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = CursorUtil.union(result, (Sequence)obj, exps, ctx);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			}
		}
		
		return result;
	}

	/**
	 * ���������е�Ԫ�����Ľ���
	 * @return Sequence
	 */
	public Sequence xor() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size < 1) {
			return new Sequence(0);
		}

		Object obj = mems.get(1);
		Sequence result;

		if (obj instanceof Sequence) {
			result = (Sequence)obj;
		} else if (obj != null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		} else {
			result = new Sequence(0);
		}

		for (int i = 2; i <= size; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				result = CursorUtil.xor(result, (Sequence)obj);
			} else if (obj != null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));
			}
		}
		
		return result;
	}
	
	/**
	 * �������еļ�����
	 * @param exp Expression �������ʽ
	 * @param opt String m�����м���
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence calc(Expression exp, String opt, Context ctx) {
		if (opt == null || opt.indexOf('m') == -1) {
			return calc(exp, ctx);
		} else {
			return MultithreadUtil.calc(this, exp, ctx);
		}
	}
	
	/**
	 * �������еļ�����
	 * @param exp Expression �������ʽ
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence calc(Expression exp, Context ctx) {
		if (exp == null) {
			return this;
		}

		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				resultMems.add(exp.calculate(ctx));
			}
		} finally {
			stack.pop();
		}

		return result;
	}

	private Sequence calc(Expression []exps, Context ctx) {
		if (exps == null) return this;

		int count = exps.length;
		if (count == 1) {
			return calc(exps[0], ctx);
		}

		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				Sequence sequence = new Sequence(count);
				resultMems.add(sequence);
				for (int e = 0; e < count; ++e) {
					sequence.add(exps[e].calculate(ctx));
				}
			}
		} finally {
			stack.pop();
		}

		return result;
	}

	/**
	 * ���ָ��Ԫ�ؼ������ʽ�����ؼ�����
	 * @param index int Ԫ������
	 * @param exp Expression �������ʽ
	 * @param ctx Context
	 * @return Object
	 */
	public Object calc(int index, Expression exp, Context ctx) {
		if (exp == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("calc" + mm.getMessage("function.invalidParam"));
		}

		int size = mems.size();
		if (index < 0) index += size + 1;
		if (index < 1 || index > size) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
		}

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			current.setCurrent(index);
			return exp.calculate(ctx);
		} finally {
			stack.pop();
		}
	}

	/**
	 * ������е�ָ��Ԫ�ؼ������ʽ���ؼ�����
	 * @param index Ԫ����ţ���1��ʼ����
	 * @param exps ����ʽ����
	 * @param ctx ����������
	 * @param outValues ����������
	 */
	public void calc(int index, Expression []exps, Context ctx, Object []outValues) {
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		current.setCurrent(index);
		stack.push(current);

		try {
			for (int i = 0, len = exps.length; i < len; ++i) {
				outValues[i] = exps[i].calculate(ctx);
			}
		} finally {
			stack.pop();
		}
	}

	/**
	 * ���ָ���Ķ�Ԫ�ؼ������ʽ�����ؼ��������ɵ�����
	 * @param seq Sequence Ԫ��λ�ù��ɵ�����
	 * @param exp Expression �������ʽ
	 * @param ctx Context ����������
	 * @return Sequence
	 */
	public Sequence calc(Sequence seq, Expression exp, Context ctx) {
		if (exp == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("calc" + mm.getMessage("function.invalidParam"));
		}

		int size = mems.size();
		int[] posArray = seq.toIntArray();
		int len = posArray.length;
		for (int i = 0; i < len; ++i) {
			if (posArray[i] < 0 || posArray[i] > size) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(posArray[i] + mm.getMessage("engine.indexOutofBound"));
			}
		}

		Sequence result = new Sequence(len);
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 0; i < len; ++i) {
				current.setCurrent(posArray[i]);
				result.add(exp.calculate(ctx));
			}

			return result;
		} finally {
			stack.pop();
		}
	}

	/**
	 * ����������������㣬����������cΪ������ǰ������������Ϊ���Ԫ�أ�
	 * @param exp ��������ʽ
	 * @param initVal ��ʼֵ
	 * @param c ��������ʽ
	 * @param opt a���������е���ֵ��Ĭ�Ϸ������һ������ֵ
	 * @param ctx ����������
	 * @return �������
	 */
	public Object iterate(Expression exp, Object initVal, Expression c, String opt, Context ctx) {
		Param param = ctx.getIterateParam();
		Object oldVal = param.getValue();
		param.setValue(initVal);
		int len = length();
		
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);
		
		try {
			if (opt == null || opt.indexOf('a') == -1) {
				if (c == null) {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						initVal = exp.calculate(ctx);
						param.setValue(initVal);
					}
				} else {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						Object obj = c.calculate(ctx);
						
						if (obj instanceof Boolean && ((Boolean)obj).booleanValue()) {
							break;
						}
						
						initVal = exp.calculate(ctx);
						param.setValue(initVal);
					}
				}

				return initVal;
			} else {
				Sequence result = new Sequence(len);
				ListBase1 resultMems = result.mems;
				if (c == null) {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						initVal = exp.calculate(ctx);
						param.setValue(initVal);
						resultMems.add(initVal);
					}
				} else {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						Object obj = c.calculate(ctx);
						
						if (obj instanceof Boolean && ((Boolean)obj).booleanValue()) {
							break;
						}
						
						initVal = exp.calculate(ctx);
						param.setValue(initVal);
						resultMems.add(initVal);
					}
				}
		
				return result;
			}
		} finally {
			stack.pop();
			param.setValue(oldVal);
		}
	}

	public void run(Expression exp, String opt, Context ctx) {
		if (opt == null || opt.indexOf('m') == -1) {
			run(exp, ctx);
		} else {
			MultithreadUtil.run(this, exp, ctx);
		}
	}
	
	/**
	 * �������ʽ�������Լ�
	 * @param exp Expression �������ʽ
	 * @param ctx Context ���������Ļ���
	 */
	public void run(Expression exp, Context ctx) {
		if (exp == null) {
			return;
		}

		int size = length();
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				exp.calculate(ctx);
			}
		} finally {
			stack.pop();
		}
	}

	/**
	 * ѭ������Ԫ�أ��������ʽ�����и�ֵ
	 * @param assignExps Expression[] ��ֵ����ʽ
	 * @param exps Expression[] ֵ����ʽ
	 * @param ctx Context
	 */
	public void run(Expression[] assignExps, Expression[] exps, Context ctx) {
		if (exps == null || exps.length == 0) {
			return;
		}

		int colCount = exps.length;
		if (assignExps == null) {
			assignExps = new Expression[colCount];
		} else if (assignExps.length != colCount) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("run" + mm.getMessage("function.invalidParam"));
		}

		Object []lastOptVals = new Object[colCount];
		for (int i = 0; i < colCount; ++i) {
			lastOptVals[i] = new Object();
		}

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1, len = length(); i <= len; ++i) {
				current.setCurrent(i);
				for (int c = 0; c < colCount; ++c) {
					if (assignExps[c] == null) {
						exps[c].calculate(ctx);
					} else {
						assignExps[c].assign(exps[c].calculate(ctx), ctx);
					}
				}
			}
		} finally {
			stack.pop();
		}
	}

	// ��ǰ����Ԫ�أ�����Ԫ�������ɵ�����, bAll�Ƿ񷵻���������������, isSorted�����Ƿ�����
	private Object firstIndexOf(Object obj, Comparator<Object> comparator,
								boolean bAll, boolean isSorted, boolean isInsertPos, int start, int end) {
		ListBase1 mems = this.mems;
		if (isSorted) {
			int index = mems.binarySearch(obj, start, end, comparator);
			if (index < 1) {
				if (bAll) {
					return new Sequence(0);
				} else if (isInsertPos) {
					return index;
				} else {
					return null;
				}
			}

			// �ҵ���һ��
			int first = index;
			while (first > start &&
				   comparator.compare(mems.get(first - 1), obj) == 0) {
				first--;
			}

			if (bAll) {
				// �ҵ����һ��
				int last = index;
				while (last < end &&
					   comparator.compare(mems.get(last + 1), obj) == 0) {
					last++;
				}

				Sequence result = new Sequence(last - first + 1);
				ListBase1 resultMems = result.mems;
				for (int i = first; i <= last; ++i) {
					resultMems.add(i);
				}
				
				return result;
			} else {
				return first;
			}
		} else {
			if (bAll) {
				Sequence result = new Sequence();
				ListBase1 resultMems = result.mems;
				for (int i = start; i <= end; ++i) {
					if (comparator.compare(mems.get(i), obj) == 0) {
						resultMems.add(i);
					}
				}
				return result;
			} else {
				for (int i = start; i <= end; ++i) {
					if (comparator.compare(mems.get(i), obj) == 0) {
						return i;
					}
				}
				
				return null;
			}
		}
	}

	// �Ӻ��濪ʼ����Ԫ�أ�����Ԫ�������ɵ�����, bAll�Ƿ񷵻���������������, isSorted�����Ƿ�����
	private Object lastIndexOf(Object obj, Comparator<Object> comparator,
							   boolean bAll, boolean isSorted, boolean isInsertPos, int start, int end) {
		ListBase1 mems = this.mems;
		if (isSorted) {
			int index = mems.binarySearch(obj, start, end, comparator);
			if (index < 1) {
				if (bAll) {
					return new Sequence(0);
				} else if (isInsertPos) {
					return index;
				} else {
					return null;
				}
			}

			// �ҵ����һ��
			int last = index;
			while (last < end &&
				   comparator.compare(mems.get(last + 1), obj) == 0) {
				last++;
			}

			if (bAll) {
				// �ҵ���ǰһ��
				int first = index;
				while (first > start &&
					   comparator.compare(mems.get(first - 1), obj) == 0) {
					first--;
				}

				Sequence result = new Sequence(last - first + 1);
				ListBase1 retMems = result.mems;
				for (int i = last; i >= first; --i) {
					retMems.add(i);
				}
				
				return result;
			} else {
				return last;
			}
		} else {
			if (bAll) {
				Sequence result = new Sequence();
				ListBase1 resultMems = result.mems;
				for (int i = end; i >= start; --i) {
					if (comparator.compare(mems.get(i), obj) == 0) {
						resultMems.add(i);
					}
				}
				
				return result;
			} else {
				for (int i = end; i >= start; --i) {
					if (comparator.compare(mems.get(i), obj) == 0) {
						return i;
					}
				}
				
				return null;
			}
		}
	}

	private Object subPos(Sequence sub, String opt) {
		if (sub.length() == 0) {
			return null;
		}

		ListBase1 mems = this.mems;
		ListBase1 subMems = sub.mems;

		int len = mems.size();
		int subLen = subMems.size();
		if (len < subLen) {
			return null;
		}

		boolean isSorted = false, isIncre = false, isContinuous = false;
		if (opt != null) {
			if (opt.indexOf('b') != -1) isSorted = true;
			if (opt.indexOf('i') != -1) isIncre = true;
			if (opt.indexOf('c') != -1) isContinuous = true;
		}

		// Ԫ�����γ�����Դ������
		if (isIncre) {
			Sequence result = new Sequence(subLen);
			ListBase1 resultMems = result.mems;

			if (isSorted) { // Դ��������
				Comparator<Object> comparator = new BaseComparator();

				int pos = 1;
				for (int t = 1; t <= subLen; ++t) {
					pos = mems.binarySearch(subMems.get(t), pos, len, comparator);
					if (pos > 0) {
						resultMems.add(pos);
						pos++;
					} else {
						return null;
					}
				}
			} else {
				int pos = 1;
				for (int t = 1; t <= subLen; ++t) {
					pos = mems.indexOf(subMems.get(t), pos, len);
					if (pos > 0) {
						resultMems.add(pos);
						pos++;
					} else {
						return null;
					}
				}
			}

			return result;
		} else if (isContinuous) {
			int maxCandidate = len - subLen + 1; // �ȽϵĴ���
			if (isSorted) {
				Object o1 = subMems.get(1);
				int candidate = 1;

				// �ҵ���һ����ȵ�Ԫ�ص����
				Next:
				while (candidate <= maxCandidate) {
					int result = Variant.compare(o1, mems.get(candidate), true);

					if (result > 0) {
						candidate++;
					} else if (result == 0) {
						for (int i = 2, j = candidate + 1; i <= subLen; ++i, ++j) {
							if (!Variant.isEquals(subMems.get(i), mems.get(j))) {
								candidate++;
								continue Next;
							}
						}

						return candidate;
					} else {
						return null;
					}
				}
			} else {
				nextCand:
				for (int candidate = 1; candidate <= maxCandidate; ++candidate) {
					for (int i = 1, j = candidate; i <= subLen; ++i, ++j) {
						if (!Variant.isEquals(subMems.get(i), mems.get(j))) {
							continue nextCand;
						}
					}

					return candidate;
				}
			}

			return null;
		} else {
			Sequence result = new Sequence(subLen);
			ListBase1 resultMems = result.mems;

			if (isSorted) { // Դ��������
				for (int t = 1; t <= subLen; ++t) {
					int pos = mems.binarySearch(subMems.get(t));
					if (pos > 0) {
						resultMems.add(pos);
					} else {
						return null;
					}
				}
			} else {
				for (int t = 1; t <= subLen; ++t) {
					int pos = mems.firstIndexOf(subMems.get(t));
					if (pos > 0) {
						resultMems.add(pos);
					} else {
						return null;
					}
				}
			}

			return result;
		}
	}

	/**
	 * ����obj�������е�λ�ã�Ĭ�Ϸ��ص�һ��
	 * @param obj Object ĳһԪ�ػ�������Ԫ����ɵ�����
	 * @param opt String ���ұ�־��a���������������ߣ�z���Ӻ�����ǰ�ң�bͬ��鲢�����ң�s���Ҳ���ʱ���ؿɲ���λ�õ��෴��
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pos(Object obj, String opt) {
		if (obj instanceof Sequence && (opt == null || opt.indexOf('p') == -1)) {
			return subPos((Sequence)obj, opt);
		}

		boolean isAll = false, isFirst = true, isNull = true, isSorted = false, isInsertPos = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)isAll = true;
			if (opt.indexOf('z') != -1)isFirst = false;
			if (opt.indexOf('n') != -1)isNull = false;
			if (opt.indexOf('b') != -1)isSorted = true;
			if (opt.indexOf('s') != -1) {
				isSorted = true;
				isInsertPos = true;
			}
		}

		int end = length();
		if (end < 1) {
			if (isAll) {
				return new Sequence(0);
			} else if (isNull) {
				return null;
			} else if (isInsertPos) {
				return new Integer(-1);
			} else {
				return new Integer(1);
			}
		}

		Comparator<Object> comparator;
		if (isSorted) {
			comparator = new BaseComparator();
		} else {
			comparator = new BaseComparator(false);
		}

		Object result;
		if (isFirst) {
			result = firstIndexOf(obj, comparator, isAll, isSorted, isInsertPos, 1, end);
		} else {
			result = lastIndexOf(obj, comparator, isAll, isSorted, isInsertPos, 1, end);
		}

		if (isNull) {
			return result;
		} else if (result != null) {
			return result;
		} else {
			return new Integer(end + 1);
		}
	}

	/**
	 * ����obj�������е�λ��
	 * @param obj Object  ĳһԪ�ػ�������Ԫ����ɵ�����
	 * @param pos int ��ʼ����λ��
	 * @param opt String  ���ұ�־��a���������������ߣ�z���Ӻ�����ǰ�ң�bͬ��鲢������
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pos(Object obj, int pos, String opt) {
		int end = length();
		if (pos < 1 || pos > end) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		}

		boolean isAll = false, isFirst = true, isNull = true, isSorted = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)isAll = true;
			if (opt.indexOf('z') != -1)isFirst = false;
			if (opt.indexOf('n') != -1)isNull = false;
			if (opt.indexOf('b') != -1)isSorted = true;
		}

		Comparator<Object> comparator;
		if (isSorted) {
			comparator = new BaseComparator();
		} else {
			comparator = new BaseComparator(false);
		}

		Object result;
		if (isFirst) {
			result = firstIndexOf(obj, comparator, isAll, isSorted, false, pos, end);
		} else {
			result = lastIndexOf(obj, comparator, isAll, isSorted, false, 1, pos);
		}

		if (isNull) {
			return result;
		} else if (result != null) {
			return result;
		} else {
			return new Integer(end + 1);
		}
	}

	/**
	 * ���а�����ʽ�����ö��ַ�����ʹ����ʽ����������ָ��ֵ��Ԫ��
	 * @param exp �������ʽ
	 * @param val ֵ
	 * @param ctx ����������
	 * @return λ�ã��Ҳ������ظ��Ĳ���λ��
	 */
	public int pfind(Expression exp, Object val, Context ctx) {
		int high = length();
		if (high == 0) {
			return -1;
		}
		
		int low = 1;
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			while (low <= high) {
				int mid = (low + high) >> 1;
				current.setCurrent(mid);
				Object obj = exp.calculate(ctx);
				int cmp = Variant.compare(obj, val, true);

				if (cmp < 0)
					low = mid + 1;
				else if (cmp > 0)
					high = mid - 1;
				else
					return mid; // key found
			}

			return -low; // key not found
		} finally {
			stack.pop();
		}
	}
	
	/**
	 * ����Ԫ�����ڵ����䣬С��A(1)����0�����ڵ���A(1)С��A(2)����1���Դ����� 
	 * @param obj
	 * @param opt r��ʹ�����ұ����䣬Ĭ������ҿ�������
	 * @return
	 */
	public int pseg(Object obj, String opt) {
		int index = mems.binarySearch(obj);
		if (index < 1) {
			return -index - 1;
		}

		if (opt == null || opt.indexOf('r') == -1) {
			return index;
		} else {
			return index - 1;
		}
	}
	
	/**
	 * �������еļ����е���Сֵ����������1��ʼ������Ĭ�Ϸ��ص�һ��
	 * @param exp Expression �������ʽ�������ʾ������
	 * @param opt String      a���������������ߣ�-���Ӻ�����ǰ��
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pmin(Expression exp, String opt, Context ctx) {
		int size = length();
		if (size == 0) {
			if (opt == null || opt.indexOf('a') == -1) {
				return null;
			} else {
				return new Sequence(0);
			}
		}

		Sequence result = calc(exp, ctx);
		return result.pmin(opt, 1, size);
	}

	/**
	 * �������еļ����е���Сֵ������
	 * @param exp Expression �������ʽ�������ʾ������
	 * @param pos int ��ʼ����λ��
	 * @param opt String a���������������ߣ�-���Ӻ�����ǰ��
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pmin(Expression exp, int pos, String opt, Context ctx) {
		int len = length();
		if (pos < 1 || pos > len) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		}

		int start = pos, end = len;
		if (opt != null && opt.indexOf('z') != -1) {
			start = 1;
			end = pos;
		}

		Sequence sequence;
		if (exp == null) {
			sequence = this;
		} else {
			sequence = new Sequence(len);
			ListBase1 valMems = sequence.mems;
			for (int i = 1; i < start; ++i) {
				valMems.add(null);
			}

			ComputeStack stack = ctx.getComputeStack();
			Current current = new Current();
			stack.push(current);

			try {
				for (int i = start; i <= end; ++i) {
					current.setCurrent(i);
					valMems.add(exp.calculate(ctx));
				}
			} finally {
				stack.pop();
			}
		}

		return sequence.pmin(opt, start, end);
	}

	/**
	 * �������еļ����е����ֵ����������1��ʼ������Ĭ�Ϸ��ص�һ��
	 * @param exp Expression �������ʽ�������ʾ������
	 * @param opt String      a���������������ߣ�-���Ӻ�����ǰ��
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pmax(Expression exp, String opt, Context ctx) {
		int size = length();
		if (size == 0) {
			if (opt == null || opt.indexOf('a') == -1) {
				return null;
			} else {
				return new Sequence(0);
			}
		}

		Sequence sequence = calc(exp, ctx);
		return sequence.pmax(opt, 1, size);
	}

	/**
	 * �������еļ����е���Сֵ������
	 * @param exp Expression �������ʽ�������ʾ������
	 * @param pos int ��ʼ����λ��
	 * @param opt String a���������������ߣ�-���Ӻ�����ǰ��
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pmax(Expression exp, int pos, String opt, Context ctx) {
		int len = length();
		if (pos < 1 || pos > len) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		}

		int start = pos, end = len;
		if (opt != null && opt.indexOf('z') != -1) {
			start = 1;
			end = pos;
		}

		Sequence sequence;
		if (exp == null) {
			sequence = this;
		} else {
			sequence = new Sequence(len);
			ListBase1 valMems = sequence.mems;
			for (int i = 1; i < start; ++i) {
				valMems.add(null);
			}

			ComputeStack stack = ctx.getComputeStack();
			Current current = new Current();
			stack.push(current);
			try {
				for (int i = start; i <= end; ++i) {
					current.setCurrent(i);
					valMems.add(exp.calculate(ctx));
				}
			} finally {
				stack.pop();
			}

			// end-len���Բ�������
		}

		return sequence.pmax(opt, start, end);
	}

	private Object pmin(String opt, int start, int end) {
		boolean bAll = false;
		boolean bLast = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true;
			if (opt.indexOf('z') != -1)bLast = true;
		}

		IntArrayList indexList = new IntArrayList();
		ListBase1 mems = this.mems;

		Object minValue = null;
		int i = start;
		for (; i <= end; ++i) {
			minValue = mems.get(i);
			if (minValue != null) { // ���Կ�ֵ
				indexList.addInt(i);
				break;
			}
		}

		for (i = i + 1; i <= end; ++i) {
			Object temp = mems.get(i);
			if (temp != null) {
				int result = Variant.compare(temp, minValue, true);
				if (result < 0) {
					minValue = temp;
					indexList.clear();
					indexList.addInt(i);
				} else if (result == 0) {
					indexList.addInt(i);
				} // ���ڲ����κδ���
			}
		}

		int resultSize = indexList.size();
		if (bAll) {
			Sequence result = new Sequence(resultSize);
			ListBase1 resultMems = result.mems;
			if (bLast) {
				for (i = resultSize - 1; i >= 0; --i) {
					resultMems.add(indexList.get(i));
				}
			} else {
				for (i = 0; i < resultSize; ++i) {
					resultMems.add(indexList.get(i));
				}
			}
			return result;
		} else {
			if (resultSize == 0) {
				return null;
			} else if (bLast) {
				return indexList.get(resultSize - 1);
			} else {
				return indexList.get(0);
			}
		}
	}

	private Object pmax(String opt, int start, int end) {
		boolean bAll = false;
		boolean bLast = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true;
			if (opt.indexOf('z') != -1)bLast = true;
		}

		ListBase1 mems = this.mems;
		IntArrayList indexList = new IntArrayList();
		Object maxValue = mems.get(start);
		indexList.addInt(start);

		for (int i = start + 1; i <= end; ++i) {
			Object temp = mems.get(i);
			int result = Variant.compare(maxValue, temp, true);
			if (result < 0) {
				maxValue = temp;
				indexList.clear();
				indexList.addInt(i);
			} else if (result == 0) {
				indexList.addInt(i);
			} // ���ڲ����κδ���
		}

		if (maxValue == null) {
			indexList.clear(); // ���Կ�ֵ
		}
		
		int resultSize = indexList.size();
		if (bAll) {
			Sequence result = new Sequence(resultSize);
			ListBase1 resultMems = result.mems;
			if (bLast) {
				for (int i = resultSize - 1; i >= 0; --i) {
					resultMems.add(indexList.get(i));
				}
			} else {
				for (int i = 0; i < resultSize; ++i) {
					resultMems.add(indexList.get(i));
				}
			}
			return result;
		} else {
			if (resultSize == 0) {
				return null;
			} else if (bLast) {
				return indexList.get(resultSize - 1);
			} else {
				return indexList.get(0);
			}
		}
	}

	/**
	 * ��������������Ԫ�ص�����
	 * @param exp Expression ������Ϊ��١����Ρ����л�ֵ
	 * @param opt String a���������������ߣ�z���Ӻ�����ǰ�ң�b�����ַ����ң�s���Ҳ���ʱ���ؿɲ���λ�õ��෴��
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pselect(Expression exp, String opt, Context ctx) {
		boolean isAll = false, isFirst = true, isNull = true, isSorted = false, isInsertPos = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)isAll = true;
			if (opt.indexOf('z') != -1)isFirst = false;
			if (opt.indexOf('n') != -1)isNull = false;
			if (opt.indexOf('b') != -1)isSorted = true;
			if (opt.indexOf('s') != -1) {
				isSorted = true;
				isInsertPos = true;
			}
		}

		if (exp == null) {
			if (isFirst) {
				return new Sequence(1, length());
			} else {
				return new Sequence(length(), 1);
			}
		}

		int size = length();
		if (size == 0) {
			if (isAll) {
				return new Sequence(0);
			} else if (isInsertPos) {
				return -1;
			} else if (isNull) {
				return null;
			} else {
				return 1;
			}
		}

		Object result;
		if (isSorted) {
			// ����ʽ�ķ���ֵΪ����������
			result = pselect0(exp, isAll, isFirst, isInsertPos, 1, size, ctx);
		} else {
			// ����ʽ�ķ�������Ϊ������
			result = pselectb(exp, isAll, isFirst, 1, size, ctx);
		}

		if (isNull) {
			return result;
		} else if (result != null) {
			return result;
		} else {
			return new Integer(size + 1);
		}
	}

	/**
	 * ��������������Ԫ�ص�����
	 * @param exp Expression ������Ϊ��١����Ρ����л�ֵ
	 * @param pos int    ��ʼ����λ��
	 * @param opt String a���������������ߣ�z���Ӻ�����ǰ�ң�b���ַ�����
	 * @param ctx Context ���������Ļ���
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pselect(Expression exp, int pos, String opt, Context ctx) {
		int len = length();
		if (pos < 1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		} else if (pos > len) {
			if (opt == null) {
				return null;
			} else if (opt.indexOf('a') != -1) {
				return new Sequence(0);
			} else if (opt.indexOf('n') != -1) {
				return new Integer(len + 1);
			} else {
				return null;
			}
		}

		boolean isAll = false, isFirst = true, isNull = true, isSorted = false, isInsertPos = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)isAll = true;
			if (opt.indexOf('z') != -1)isFirst = false;
			if (opt.indexOf('n') != -1)isNull = false;
			if (opt.indexOf('b') != -1)isSorted = true;
			if (opt.indexOf('s') != -1) {
				isSorted = true;
				isInsertPos = true;
			}
		}
		
		if (exp == null) {
			if (isFirst) {
				return new Sequence(pos, len);
			} else {
				return new Sequence(pos, 1);
			}
		}

		int start = pos, end = len;
		if (!isFirst) {
			start = 1;
			end = pos;
		}

		Object result;
		if (isSorted) {
			// ����ʽ�ķ���ֵΪ����������
			result = pselect0(exp, isAll, isFirst, isInsertPos, start, end, ctx);
		} else {
			// ����ʽ�ķ�������Ϊ������
			result = pselectb(exp, isAll, isFirst, start, end, ctx);
		}

		if (isNull) {
			return result;
		} else if (result != null) {
			return result;
		} else {
			return new Integer(len + 1);
		}
	}

	/**
	 * ����ʹ�������ʽֵ��ȵ�Ԫ�ص�λ��
	 * @param fltExps Expression[] ��������ʽ
	 * @param vals Object[] ֵ
	 * @param pos int ��ʼ����λ��
	 * @param opt String a���������������ߣ�z���Ӻ�����ǰ�ң�b�����ַ�����
	 * @param ctx Context
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pselect(Expression[] fltExps, Object[] vals,
						  int pos, String opt, Context ctx) {
		if (fltExps == null || fltExps.length == 0) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pselect" +
								  mm.getMessage("function.paramValNull"));
		}

		if (vals == null || vals.length != fltExps.length) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pselect" +
								  mm.getMessage("function.paramCountNotMatch"));
		}

		int len = length();
		if (pos < 1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		} else if (pos > len) {
			if (opt == null) {
				return null;
			} else if (opt.indexOf('a') != -1) {
				return new Sequence(0);
			} else if (opt.indexOf('n') != -1) {
				return new Integer(len + 1);
			} else {
				return null;
			}
		}

		if (opt == null || opt.indexOf('z') == -1) {
			return pselect(fltExps, vals, opt, pos, len, ctx);
		} else {
			return pselect(fltExps, vals, opt, 1, pos, ctx);
		}
	}

	/**
	 * ����ʹ�������ʽֵ��ȵ�Ԫ�ص�λ��
	 * @param fltExps Expression[] ��������ʽ
	 * @param vals Object[] ֵ����ʽ
	 * @param opt String a���������������ߣ�-���Ӻ�����ǰ�ң�b�����ַ����ң�s���Ҳ���ʱ���ؿɲ���λ�õ��෴��
	 * @param ctx Context
	 * @return ���У�����aѡ�����������aѡ��
	 */
	public Object pselect(Expression[] fltExps, Object[] vals, String opt, Context ctx) {
		if (fltExps == null || fltExps.length == 0) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pselect" + mm.getMessage("function.paramValNull"));
		}

		if (vals == null || vals.length != fltExps.length) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pselect" + mm.getMessage("function.paramCountNotMatch"));
		}

		int size = length();
		if (size == 0) {
			if (opt == null) {
				return null;
			} else if (opt.indexOf('s') != -1) {
				return -1;
			} else if (opt.indexOf('n') != -1) {
				return 1;
			} else if (opt.indexOf('a') != -1) {
				return new Sequence(0);
			} else {
				return null;
			}
		}

		return pselect(fltExps, vals, opt, 1, size, ctx);
	}

	private Object pselect(Expression[] fltExps, Object[] vals,
						   String opt, int start, int end, Context ctx) {
		boolean bAll = false, bLast = false, isSorted = false, isInsertPos = false;
		Object NULL = null;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true;
			if (opt.indexOf('z') != -1)bLast = true;
			if (opt.indexOf('b') != -1)isSorted = true;
			if (opt.indexOf('s') != -1) {
				isSorted = true;
				isInsertPos = true;
			}
			
			if (opt.indexOf('n') != -1) NULL = new Integer(length() + 1);
		}

		int colCount = fltExps.length;
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			if (isSorted) { // ����
				int low = start, high = end;
				int pos = -1;

				while (low <= high) {
					int mid = (low + high) >> 1;
					current.setCurrent(mid);
					int cmp = 0;

					for (int c = 0; c < colCount; ++c) {
						Object flt = fltExps[c].calculate(ctx);
						if ((cmp = Variant.compare(flt, vals[c], true)) !=
							0) {
							break;
						}
					}

					if (cmp < 0) {
						low = mid + 1;
					} else if (cmp > 0) {
						high = mid - 1;
					} else {
						pos = mid; // key found
						break;
					}
				}

				if (pos == -1) {
					if (bAll) {
						return new Sequence(0);
					} else if (isInsertPos) {
						return -low;
					} else {
						return NULL;
					}
				}

				int first = 0;
				int last = 0;

				// �ҵ���һ��ʹexp����0��Ԫ�ص�����
				if (bAll || !bLast) {
					first = pos;
					Next:while (first > start) {
						current.setCurrent(first - 1);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								break Next;
							}
						}

						first--;
					}
				}

				// �ҵ����һ��ʹexp����0��Ԫ�ص�����
				if (bAll || bLast) {
					last = pos;
					Next:while (last < end) {
						current.setCurrent(last + 1);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								break Next;
							}
						}

						last++;
					}
				}

				if (bAll) {
					Sequence result = new Sequence(last - first + 1);
					ListBase1 resultMems = result.mems;
					if (bLast) {
						for (; last >= first; --last) {
							resultMems.add(last);
						}
					} else {
						for (; first <= last; ++first) {
							resultMems.add(first);
						}
					}
					
					return result;
				} else {
					if (bLast) {
						return last;
					} else {
						return first;
					}
				}
			} else { // ����
				Sequence result = bAll ? new Sequence() : null;
				if (bLast) {
					Next:
					for (int i = end; i >= start; --i) {
						current.setCurrent(i);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								continue Next;
							}
						}

						if (!bAll) {
							return i;
						}
						
						result.add(i);
					}
				} else {
					Next:
					for (int i = start; i <= end; ++i) {
						current.setCurrent(i);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								continue Next;
							}
						}

						if (!bAll) {
							return i;
						}
						
						result.add(i);
					}
				}

				if (bAll) {
					return result;
				} else {
					return NULL;
				}
			}
		} finally {
			stack.pop();
		}
	}
	
	// ���а�exp���򣬶��ֲ���ʹexp�ļ���ֵΪval��Ԫ�ص�λ�ã��Ҳ������ز���λ��
	private int pselectb(Node exp, Object val, boolean bLast, int start, int end, Context ctx) {
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			int low = start, high = end;
			int pos = -1;

			while (low <= high) {
				int mid = (low + high) >> 1;
				current.setCurrent(mid);
				Object flt = exp.calculate(ctx);
				int cmp = Variant.compare(flt, val, true);

				if (cmp < 0) {
					low = mid + 1;
				} else if (cmp > 0) {
					high = mid - 1;
				} else {
					if (bLast) {
						if (high - mid > 15) {
							low = mid;
						} else {
							pos = mid; // key found
							break;
						}
					} else {
						if (mid - low > 15) {
							high = mid;
						} else {
							pos = mid; // key found
							break;
						}
					}
				}
			}

			if (pos == -1) {
				return -low;
			}

			if (bLast) {
				// �ҵ����һ��ʹexp����0��Ԫ�ص�����
				for (++pos; pos <= high; ++pos) {
					current.setCurrent(pos);
					Object flt = exp.calculate(ctx);
					if (!Variant.isEquals(flt, val)) {
						break;
					}
				}
				
				return pos - 1;
			} else {
				// �ҵ���һ��ʹexp����0��Ԫ�ص�����
				for (--pos; pos >= low; --pos) {
					current.setCurrent(pos);
					Object flt = exp.calculate(ctx);
					if (!Variant.isEquals(flt, val)) {
						break;
					}
				}
				
				return pos + 1;
			}
		} finally {
			stack.pop();
		}
	}

	// ����ʹ����ʽΪtrue��Ԫ�ص���������1��ʼ������Ĭ�Ϸ��ص�һ��
	private Object pselectb(Expression exp, boolean isAll, boolean isFirst, 
			int start, int end, Context ctx) {
		Sequence result = isAll ? new Sequence() : null;
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			if (isFirst) {
				for (int i = start; i <= end; ++i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (Variant.isTrue(obj)) {
						if (!isAll) {
							return i;
						}

						result.add(i);
					}
				}
			} else {
				for (int i = end; i >= start; --i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (Variant.isTrue(obj)) {
						if (!isAll) {
							return i;
						}

						result.add(i);
					}
				}
			}
		} finally {
			stack.pop();
		}

		if (isAll) {
			return result;
		} else {
			return null;
		}
	}

	// �ٶ����еļ��������򣬷���ʹ����ʽ�ķ���ֵΪ0��Ԫ�ص���������1��ʼ������Ĭ�Ϸ��ص�һ��
	// opt a���������������ߣ�z���Ӻ�����ǰ��
	private Object pselect0(Expression exp, boolean isAll, boolean isFirst, 
			boolean isInsertPos, int start, int end, Context ctx) {
		int first = 0;
		int last = 0;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			// ȡ����һ�������һ��Ԫ�ؼ������ʽ���ж������ǽ���
			current.setCurrent(start);
			Object objFirst = exp.calculate(ctx);

			current.setCurrent(end);
			Object objLast = exp.calculate(ctx);

			if (!(objFirst instanceof Number) || !(objLast instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntExp"));
			}

			int valFirst = ((Number)objFirst).intValue();
			int valLast = ((Number)objLast).intValue();

			// �����Сֵ����0�������ֵС����0��û������������Ԫ��
			if (valFirst > 0) {
				if (isAll) {
					return new Sequence(0);
				} else if (isInsertPos) {
					return -1;
				} else {
					return null;
				}
			} else if (valLast < 0) {
				if (isAll) {
					return new Sequence(0);
				} else if (isInsertPos) {
					return -end - 1;
				} else {
					return null;
				}
			}

			if (valFirst == valLast) { // ������0
				first = start;
				last = end;
			} else {
				// ���ֲ���ʹexp����0��Ԫ�ص�����
				int low = start, high = end;
				int pos = -1;

				while (low <= high) {
					int mid = (low + high) >> 1;
					current.setCurrent(mid);
					Object obj = exp.calculate(ctx);
					if (!(obj instanceof Number)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.needIntExp"));
					}

					int value = ((Number)obj).intValue();
					if (value < 0) {
						low = mid + 1;
					} else if (value > 0) {
						high = mid - 1;
					} else {
						pos = mid; // key found
						break;
					}
				}

				if (pos == -1) {
					if (isAll) {
						return new Sequence(0);
					} else if (isInsertPos) {
						return -low;
					} else {
						return null;
					}
				}

				// �ҵ���һ��ʹexp����0��Ԫ�ص�����
				if (isAll || isFirst) {
					first = pos;
					while (first > start) {
						current.setCurrent(first - 1);
						Object obj = exp.calculate(ctx);
						if (!(obj instanceof Number)) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(mm.getMessage("engine.needIntExp"));
						}

						if (((Number)obj).intValue() == 0) {
							first--;
						} else {
							break;
						}
					}
				}

				// �ҵ����һ��ʹexp����0��Ԫ�ص�����
				if (isAll || !isFirst) {
					last = pos;
					while (last < end) {
						current.setCurrent(last + 1);
						Object obj = exp.calculate(ctx);
						if (!(obj instanceof Number)) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(mm.getMessage("engine.needIntExp"));
						}

						if (((Number)obj).intValue() == 0) {
							last++;
						} else {
							break;
						}
					}
				}
			}
		} finally {
			stack.pop();
		}

		if (isAll) {
			Sequence result = new Sequence(last - first + 1);
			ListBase1 resultMems = result.mems;
			if (isFirst) {
				for (; first <= last; ++first) {
					resultMems.add(first);
				}
			} else {
				for (; last >= first; --last) {
					resultMems.add(last);
				}
			}
			
			return result;
		} else {
			if (isFirst) {
				return first;
			} else {
				return last;
			}
		}
	}

	/**
	 * ������ʹ����ʽexpֵ��С��Ԫ�ع��ɵ����У�Ĭ�Ϸ��ص�һ��
	 * @param exp Expression �������ʽ
	 * @param opt String a���������У�z���������һ����x�����ز
	 * @param ctx Context ����������
	 * @return Sequence
	 */
	public Object minp(Expression exp, String opt, Context ctx) {
		boolean bAll = false, bLast = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true; // ѡ�����е�
			if (opt.indexOf('z') != -1)bLast = true; // �Ӻ�ʼ
		}

		int len = length();
		if (len == 0) {
			return bAll ? new Sequence(0) : null;
		}

		Sequence values = calc(exp, ctx);
		ListBase1 valMems = values.mems;

		// ȡ����Сֵ������
		Object minValue = null;
		int i = 1;
		for (; i <= len; ++i) {
			minValue = valMems.get(i);
			if (minValue != null) { // ���Կ�ֵ
				break;
			}
		}

		if (i > len) {
			// ȫ��null
			if (bAll) {
				return new Sequence();
			} else {
				return null;
			}
		}

		if (bAll) {
			IntArrayList indexList = new IntArrayList();
			indexList.addInt(i);
			for (++i; i <= len; ++i) {
				Object temp = valMems.get(i);
				if (temp != null) {
					int result = Variant.compare(temp, minValue, true);
					if (result < 0) {
						minValue = temp;
						indexList.clear();
						indexList.addInt(i);
					} else if (result == 0) {
						indexList.addInt(i);
					} // ���ڲ����κδ���
				}
			}
			
			int count = indexList.size();
			ListBase1 mems = this.mems;
			Sequence result = new Sequence(count);
			ListBase1 resultMems = result.mems;
			
			if (bLast) { // ����ѡ��index��ָ����Ԫ��
				for (i = count - 1; i >= 0; --i) {
					resultMems.add(mems.get(indexList.getInt(i)));
				}
			} else { // ˳��ѡ��index��ָ����Ԫ��
				for (i = 0; i < count; ++i) {
					resultMems.add(mems.get(indexList.getInt(i)));
				}
			}

			return result;
		} else {
			int q = i;
			if (bLast) {
				for (++i; i <= len; ++i) {
					Object temp = valMems.get(i);
					if (temp != null) {
						int result = Variant.compare(temp, minValue, true);
						if (result < 0) {
							minValue = temp;
							q = i;
						} else if (result == 0) {
							q = i;
						} // ���ڲ����κδ���
					}
				}
			} else {
				for (++i; i <= len; ++i) {
					Object temp = valMems.get(i);
					if (temp != null && Variant.compare(temp, minValue, true) < 0) {
						minValue = temp;
						q = i;
					}
				}
			}
			
			return mems.get(q);
		}
	}

	/**
	 * ������ʹָ������ʽֵ����Ԫ�ع��ɵ����У�Ĭ�Ϸ��ص�һ��
	 * @param exp Expression �������ʽ
	 * @param opt String a���������У�z���������һ����x�����ز
	 * @param ctx Context ����������
	 * @return Sequence
	 */
	public Object maxp(Expression exp, String opt, Context ctx) {
		boolean bAll = false, bLast = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)bAll = true; // ѡ�����е�
			if (opt.indexOf('z') != -1)bLast = true; // �Ӻ�ʼ
		}

		int len = length();
		if (len == 0) {
			return bAll ? new Sequence(0) : null;
		}

		Sequence values = calc(exp, ctx);
		ListBase1 valMems = values.mems;
		
		// ȡ�����ֵ������
		Object maxValue = null;
		int i= 1;
		for (; i <= len; ++i) {
			maxValue = valMems.get(i);
			if (maxValue != null) { // ���Կ�ֵ
				break;
			}
		}
		
		if (i > len) {
			// ȫ��null
			if (bAll) {
				return new Sequence();
			} else {
				return null;
			}
		}

		if (bAll) {
			IntArrayList indexList = new IntArrayList();
			indexList.addInt(i);
			
			for (++i; i <= len; ++i) {
				Object temp = valMems.get(i);
				int result = Variant.compare(maxValue, temp, true);
				if (result < 0) {
					maxValue = temp;
					indexList.clear();
					indexList.addInt(i);
				} else if (result == 0) {
					indexList.addInt(i);
				} // ���ڲ����κδ���
			}
			
			int count = indexList.size();
			ListBase1 mems = this.mems;
			Sequence result = new Sequence(count);
			ListBase1 resultMems = result.mems;
			
			if (bLast) { // ����ѡ��index��ָ����Ԫ��
				for (i = count - 1; i >= 0; --i) {
					resultMems.add(mems.get(indexList.getInt(i)));
				}
			} else { // ˳��ѡ��index��ָ����Ԫ��
				for (i = 0; i < count; ++i) {
					resultMems.add(mems.get(indexList.getInt(i)));
				}
			}

			return result;
		} else {
			int q = i;
			if (bLast) {
				for (++i; i <= len; ++i) {
					Object temp = valMems.get(i);
					int result = Variant.compare(maxValue, temp, true);
					if (result < 0) {
						maxValue = temp;
						q = i;
					} else if (result == 0) {
						q = i;
					} // ���ڲ����κδ���
				}
			} else {
				for (++i; i <= len; ++i) {
					Object temp = valMems.get(i);
					if (Variant.compare(maxValue, temp, true) < 0) {
						maxValue = temp;
						q = i;
					}
				}
			}
			
			return mems.get(q);
		}
	}
	
	private Sequence selectNotNull() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				resultMems.add(obj);
			}
		}

		return result;
	}

	private Sequence selectNotNull(Expression exp, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= size; ++i) {
				current.setCurrent(i);
				if (exp.calculate(ctx) != null) {
					resultMems.add(mems.get(i));
				}
			}
		} finally {
			stack.pop();
		}

		return result;
	}

	/**
	 * ��������������Ԫ�ص�����
	 * @param exp Expression ������Ϊ��١����Ρ����л�ֵ
	 * @param opt String 1�����ص�һ����z���Ӻ���ң� bʹ�ö��ַ����ң�o���޸����б�����t�������Ϊ��ʱ���ؿ������
	 * c���ҵ���һ��������������ֹͣ��r���ҵ���һ������������ȡ�����
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Object select(Expression exp, String opt, Context ctx) {
		boolean isAll = true, isForward = true, isBool = true, isOrg = false;
		if (opt != null) {
			if (opt.indexOf('m') != -1) {
				return MultithreadUtil.select(this, exp, ctx);
			}
			
			if (opt.indexOf('1') != -1) isAll = false;
			if (opt.indexOf('z') != -1) isForward = false;
			if (opt.indexOf('b') != -1) isBool = false;
			if (opt.indexOf('o') != -1) isOrg = true;
		}

		if (length() == 0) {
			if (isOrg) {
				return this;
			} else if (isAll) {
				return new Sequence(0);
			} else {
				return null;
			}
		}

		if (exp == null) { // ѡ������
			if (isForward) {
				if (isOrg) {
					return this;
				} else {
					return new Sequence(this);
				}
			} else {
				if (isOrg) {
					this.mems = rvs().mems;
					return this;
				} else {
					return rvs();
				}
			}
		}

		if (isBool) { // ����ʹ����ʽΪ���Ԫ��
			return selectb(exp, opt, ctx);
		}

		DataStruct ds = null;
		Object val = getMem(1);
		if (val instanceof Record) {
			ds = ((Record)val).dataStruct();
		}
		
		Regions regions = binarySelect(exp.getHome(), ds, ctx);
		if (regions == null) {
			// ����ʹ����ʽֵΪ0��Ԫ��
			return select0(exp, opt, ctx);
		} else {
			ArrayList<Region> list = regions.getRegionList();
			int total = 0;
			for (Region region : list) {
				total += region.end - region.start + 1;
			}
			
			ListBase1 mems = this.mems;
			Sequence seq = new Sequence(total);
			for (Region region : list) {
				for (int i = region.start, end = region.end; i <= end; ++i) {
					seq.add(mems.get(i));
				}
			}
			
			return seq;
		}
	}

	// ���ds�����ж�node�Ƿ���ds���ֶΣ������ж�node�Ƿ���~
	private boolean isField(DataStruct ds, Node node) {
		// [f1,f2]���ֶ�����
		if (node instanceof ValueList) {
			if (ds != null) {
				ValueList valueList = (ValueList)node;
				Expression[] exps = valueList.getParamExpressions("select", true);
				if (exps[0] != null) {
					return isField(ds, exps[0].getHome());
				} else {
					return false;
				}
			} else {
				return false;
			}
		} else if (node instanceof UnknownSymbol) {
			if (ds != null) {
				String name = ((UnknownSymbol)node).getName();
				if (name.charAt(0) == '\'' && name.charAt(name.length() - 1) == '\'') {
					name = name.substring(1, name.length() - 1);
				}
				
				return ds.getFieldIndex(name) != -1;
			} else {
				return false;
			}
		} else if (node instanceof DotOperator) {
			node = node.getRight();
			if (ds != null && node instanceof FieldRef) {
				String name = ((FieldRef)node).getName();
				if (name.charAt(0) == '\'' && name.charAt(name.length() - 1) == '\'') {
					name = name.substring(1, name.length() - 1);
				}
				
				return ds.getFieldIndex(name) != -1;
			} else if (ds == null && node instanceof CurrentElement) {
				return true;
			} else {
				return false;
			}
		} else if (node instanceof FieldId) {
			return ds != null;
		} else if (ds == null && node instanceof CurrentElement) {
			return true;
		} else {
			return false;
		}
	}
	
	// �������
	private Regions binarySelect(Node node, DataStruct ds, Context ctx) {
		try {
			Node fieldNode;
			Object value;
			int operator;
			if (node instanceof Equals) {
				operator = IFilter.EQUAL;
			} else if (node instanceof Greater) {
				operator = IFilter.GREATER;
			} else if (node instanceof NotSmaller) {
				operator = IFilter.GREATER_EQUAL;
			} else if (node instanceof Smaller) {
				operator = IFilter.LESS;
			} else if (node instanceof NotGreater) {
				operator = IFilter.LESS_EQUAL;
			} else if (node instanceof NotEquals) {
				operator = IFilter.NOT_EQUAL;
			} else if (node instanceof And) {
				Regions r1 = binarySelect(node.getLeft(), ds, ctx);
				Regions r2 = binarySelect(node.getRight(), ds, ctx);
				return r1.and(r2);
			} else if (node instanceof Or) {
				Regions r1 = binarySelect(node.getLeft(), ds, ctx);
				Regions r2 = binarySelect(node.getRight(), ds, ctx);
				return r1.or(r2);
			} else {
				return null;
			}
			
			Node left = node.getLeft();
			Node right = node.getRight();
			if (isField(ds, left)) {
				fieldNode = left;
				value = right.calculate(ctx);
			} else if (isField(ds, right)) {
				fieldNode = right;
				value = left.calculate(ctx);
				operator = IFilter.getInverseOP(operator);
			} else {
				return null;
			}
			
			Regions regions = new Regions();
			int len = length();
			if (operator == IFilter.EQUAL) {
				int start = pselectb(fieldNode, value, false, 1, len, ctx);
				if (start < 1) {
					return regions;
				}
				
				int end = pselectb(fieldNode, value, true, start, len, ctx);
				regions.addRegion(new Region(start, end));
			} else if (operator == IFilter.GREATER) {
				int start = pselectb(fieldNode, value, true, 1, len, ctx);
				if (start < 1) {
					start = -start;
				} else {
					start++;
				}
				
				if (start <= len) {
					regions.addRegion(new Region(start, len));
				}
			} else if (operator == IFilter.GREATER_EQUAL) {
				int start = pselectb(fieldNode, value, false, 1, len, ctx);
				if (start < 1) {
					start = -start;
				}
				
				if (start <= len) {
					regions.addRegion(new Region(start, len));
				}
			} else if (operator == IFilter.LESS) {
				int end = pselectb(fieldNode, value, false, 1, len, ctx);
				if (end < 1) {
					end = -end;
				}
				
				end--;
				if (end >= 1) {
					regions.addRegion(new Region(1, end));
				}
			} else if (operator == IFilter.LESS_EQUAL) {
				int end = pselectb(fieldNode, value, true, 1, len, ctx);
				if (end < 1) {
					end = -end - 1;
				}
				
				if (end >= 1) {
					regions.addRegion(new Region(1, end));
				}
			} else { // IFilter.NOT_EQUAL
				int start = pselectb(fieldNode, value, false, 1, len, ctx);
				if (start < 1) {
					regions.addRegion(new Region(1, len));
				} else {
					int end = pselectb(fieldNode, value, true, start, len, ctx);
					if (start > 1) {
						regions.addRegion(new Region(1, start - 1));
					}
					
					if (end < len) {
						regions.addRegion(new Region(end + 1, len));
					}
				}
			}
			return regions;
		} catch (Exception e) {
			return null;
		}
	}
	
	// ����ʹ����ʽexpΪtrue��Ԫ�ع��ɵ����У�Ĭ�Ϸ�������
	// opt 1�����ص�һ����z���������һ����c���ҵ���һ��������������ֹͣ��r���ҵ���һ������������ȡ�����
	private Object selectb(Expression exp, String opt, Context ctx) {
		boolean bOne = false, bLast = false, isOrg = false, returnTable = false, continuous = false, rc = false;
		if (opt != null) {
			if (opt.indexOf('1') != -1) {
				bOne = true;
			} else if (opt.indexOf('r') != -1) {
				rc = true;
			} else if (opt.indexOf('c') != -1) {
				continuous = true;
			}
			
			if (opt.indexOf('z') != -1)bLast = true;
			if (opt.indexOf('o') != -1)isOrg = true;
			if (opt.indexOf('t') != -1) returnTable = true;
			
			if (opt.indexOf('i') != -1 && getIndexTable() != null) {
				IndexTable index = getIndexTable();
				if (index instanceof HashIndexTable) {
					return ((HashIndexTable)index).select(exp, ctx);
				} else if (index instanceof HashArrayIndexTable) {
					return ((HashArrayIndexTable)index).select(exp, ctx);
				}
			}
		}

		ListBase1 mems = this.mems;
		int len = mems.size();
		Sequence result = bOne ? null : new Sequence();

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			if (!bLast) {
				for (int i = 1; i <= len; ++i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (Variant.isTrue(obj)) {
						if (rc) {
							// �ҵ���һ������������ȡ�����
							result.add(mems.get(i));
							for (++i; i <= len; ++i) {
								result.add(mems.get(i));
							}
						} else if (bOne) {
							if (isOrg) {
								this.mems = new ListBase1(1);
								this.mems.add(mems.get(i));
								return this;
							} else {
								return mems.get(i);
							}
						} else {
							result.add(mems.get(i));
						}
					} else if (continuous) {
						// ֻ��ǰ����������������
						break;
					}
				}
			} else {
				for (int i = len; i > 0; --i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (Variant.isTrue(obj)) {
						if (rc) {
							// �Ӻ����ҵ���һ������������ȡ����һ��
							result.add(mems.get(i));
							for (--i; i > 0; --i) {
								result.add(mems.get(i));
							}
						} else if (bOne) {
							if (isOrg) {
								this.mems = new ListBase1(1);
								this.mems.add(mems.get(i));
								return this;
							} else {
								return mems.get(i);
							}
						} else {
							result.add(mems.get(i));
						}
					} else if (continuous) {
						// ֻ�Һ�����������������
						break;
					}
				}
			}
		} finally {
			stack.pop();
		}

		if (bOne) {
			if (isOrg) {
				this.mems = new ListBase1(0);
				return this;
			} else {
				return null;
			}
		} else {
			if (isOrg) {
				this.mems = result.mems;
				return this;
			} else {
				if (returnTable && result.length() == 0) {
					Object obj = ifn();
					if (obj instanceof Record) {
						return new Table(((Record)obj).dataStruct());
					} else {
						return result;
					}
				} else {
					return result;
				}
			}
		}
	}

	// �ٶ����еļ��������򣬷���ʹ����ʽ�ķ���ֵΪ0��Ԫ�ع��ɵ�����
	// opt 1��ѡ��һ����z���Ӻ�����ǰ��
	private Object select0(Expression exp, String opt, Context ctx) {
		boolean bOne = false, bLast = false, isOrg = false, returnTable = false;
		if (opt != null) {
			if (opt.indexOf('1') != -1)bOne = true;
			if (opt.indexOf('z') != -1)bLast = true;
			if (opt.indexOf('o') != -1) isOrg = true;
			if (opt.indexOf('t') != -1) returnTable = true;
		}

		int size = length();
		int first = 0;
		int last = 0;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			// ȡ����һ�������һ��Ԫ�ؼ������ʽ���ж������ǽ���
			current.setCurrent(1);
			Object objFirst = exp.calculate(ctx);

			current.setCurrent(size);
			Object objLast = exp.calculate(ctx);

			if (!(objFirst instanceof Number) || !(objLast instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntExp"));
			}

			int valFirst = ((Number)objFirst).intValue();
			int valLast = ((Number)objLast).intValue();

			// �����Сֵ����0�������ֵС����0��û������������Ԫ��
			if (valFirst > 0 || valLast < 0) {
				if (isOrg) {
					this.mems = new ListBase1(0);
					return this;
				} else if (bOne) {
					return null;
				} else if (returnTable) {
					Object obj = ifn();
					if (obj instanceof Record) {
						return new Table(((Record)obj).dataStruct());
					} else {
						return new Sequence(0);
					}
				} else {
					return new Sequence(0);
				}
			}

			if (valFirst == valLast) { // ������0
				first = 1;
				last = size;
			} else {
				// ���ֲ���ʹexp����0��Ԫ�ص�����
				int low = 1, high = size;
				int pos = -1;

				while (low <= high) {
					int mid = (low + high) >> 1;
					current.setCurrent(mid);
					Object obj = exp.calculate(ctx);
					if (!(obj instanceof Number)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.needIntExp"));
					}

					int value = ((Number)obj).intValue();
					if (value < 0) {
						low = mid + 1;
					} else if (value > 0) {
						high = mid - 1;
					} else {
						pos = mid; // key found
						break;
					}
				}

				if (pos == -1) {
					if (isOrg) {
						this.mems = new ListBase1(0);
						return this;
					} else if (bOne) {
						return null;
					} else if (returnTable) {
						Object obj = ifn();
						if (obj instanceof Record) {
							return new Table(((Record)obj).dataStruct());
						} else {
							return new Sequence(0);
						}
					} else {
						return new Sequence(0);
					}
				}

				// �ҵ���һ��ʹexp����0��Ԫ�ص�����
				if (!bOne || !bLast) {
					first = pos;
					while (first > 1) {
						current.setCurrent(first - 1);
						Object obj = exp.calculate(ctx);
						if (!(obj instanceof Number)) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(mm.getMessage("engine.needIntExp"));
						}

						if (((Number)obj).intValue() == 0) {
							first--;
						} else {
							break;
						}
					}
				}

				// �ҵ����һ��ʹexp����0��Ԫ�ص�����
				if (!bOne || bLast) {
					last = pos;
					while (last < size) {
						current.setCurrent(last + 1);
						Object obj = exp.calculate(ctx);
						if (!(obj instanceof Number)) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(mm.getMessage("engine.needIntExp"));
						}

						if (((Number)obj).intValue() == 0) {
							last++;
						} else {
							break;
						}
					}
				}
			}
		} finally {
			stack.pop();
		}

		if (bOne) {
			if (isOrg) {
				Object val = bLast ? getMem(last) : getMem(first);
				this.mems = new ListBase1(1);
				this.mems.add(val);
				return this;
			} else {
				return bLast ? getMem(last) : getMem(first);
			}
		} else {
			Sequence result = new Sequence(last - first + 1);
			ListBase1 mems = this.mems;
			ListBase1 resultMems = result.mems;
			if (bLast) {
				for (; last >= first; --last) {
					resultMems.add(mems.get(last));
				}
			} else {
				for (; first <= last; ++first) {
					resultMems.add(mems.get(first));
				}
			}

			if (isOrg) {
				this.mems = resultMems;
				return this;
			} else {
				if (returnTable && result.length() == 0) {
					Object obj = ifn();
					if (obj instanceof Record) {
						return new Table(((Record)obj).dataStruct());
					} else {
						return result;
					}
				} else {
					return result;
				}
			}
		}
	}

	/**
	 * ����ʹ�������ʽֵ��ȵ�Ԫ��
	 * @param fltExps Expression[] ��������ʽ
	 * @param vals Object[] ֵ
	 * @param opt String 1�����ص�һ����z���Ӻ���ң�x�����ز��bʹ�ö��ַ�����
	 * @param ctx Context
	 * @return Object
	 */
	public Object select(Expression[] fltExps, Object[] vals, String opt,
						 Context ctx) {
		if (fltExps == null || fltExps.length == 0) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("select" + mm.getMessage("function.paramValNull"));
		}

		int colCount = fltExps.length;
		if (vals == null || vals.length != colCount) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("select" + mm.getMessage("function.paramCountNotMatch"));
		}

		boolean bOne = false, bLast = false, isSorted = false, isOrg = false, returnTable = false, continuous = false, rc = false;
		if (opt != null) {
			if (opt.indexOf('m') != -1) {
				return MultithreadUtil.select(this, fltExps, vals, opt, ctx);
			}
			
			if (opt.indexOf('1') != -1) {
				bOne = true;
			} else if (opt.indexOf('r') != -1) {
				rc = true;
			} else if (opt.indexOf('c') != -1) {
				continuous = true;
			}
			
			if (opt.indexOf('z') != -1) bLast = true;
			if (opt.indexOf('b') != -1) isSorted = true;
			if (opt.indexOf('o') != -1) isOrg = true;
			if (opt.indexOf('t') != -1) returnTable = true;
		}

		final int end = length();
		if (end == 0) {
			if (bOne) {
				return isOrg ? this : null;
			} else {
				return this;
			}
		}

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			if (isSorted) { // ����
				int low = 1, high = end;
				int pos = -1;

				while (low <= high) {
					int mid = (low + high) >> 1;
					current.setCurrent(mid);
					int cmp = 0;

					for (int c = 0; c < colCount; ++c) {
						Object flt = fltExps[c].calculate(ctx);
						if ((cmp = Variant.compare(flt, vals[c], true)) !=
							0) {
							break;
						}
					}

					if (cmp < 0) {
						low = mid + 1;
					} else if (cmp > 0) {
						high = mid - 1;
					} else {
						pos = mid; // key found
						break;
					}
				}

				if (pos == -1) {
					if (isOrg) {
						this.mems = new ListBase1(0);
						return this;
					} else if (bOne) {
						return null;
					} else if (returnTable) {
						Object obj = ifn();
						if (obj instanceof Record) {
							return new Table(((Record)obj).dataStruct());
						} else {
							return new Sequence(0);
						}
					} else {
						return new Sequence(0);
					}
				}

				int first = 0;
				int last = 0;

				// �ҵ���һ��ʹexp����0��Ԫ�ص�����
				if (!bOne || !bLast) {
					first = pos;
					Next:
					while (first > 1) {
						current.setCurrent(first - 1);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								break Next;
							}
						}

						first--;
					}
				}

				// �ҵ����һ��ʹexp����0��Ԫ�ص�����
				if (!bOne || bLast) {
					last = pos;
					Next:
					while (last < end) {
						current.setCurrent(last + 1);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								break Next;
							}
						}

						last++;
					}
				}

				if (bOne) {
					if (isOrg) {
						Object val = bLast ? getMem(last) : getMem(first);
						this.mems = new ListBase1(1);
						this.mems.add(val);
						return this;
					} else {
						return bLast ? getMem(last) : getMem(first);
					}
				} else {
					Sequence result = new Sequence(last - first + 1);
					ListBase1 mems = this.mems;
					ListBase1 resultMems = result.mems;
					if (bLast) {
						for (; last >= first; --last) {
							resultMems.add(mems.get(last));
						}
					} else {
						for (; first <= last; ++first) {
							resultMems.add(mems.get(first));
						}
					}

					if (isOrg) {
						this.mems = resultMems;
						return this;
					} else {
						if (returnTable && result.length() == 0) {
							Object obj = ifn();
							if (obj instanceof Record) {
								return new Table(((Record)obj).dataStruct());
							} else {
								return result;
							}
						} else {
							return result;
						}
					}
				}
			} else { // ����
				Sequence result = bOne ? null : new Sequence();
				if (bLast) {
					Next:
					for (int i = end; i > 0; --i) {
						Object cur = mems.get(i);
						current.setCurrent(i);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								if (continuous) {
									// ֻ�Һ�����������������
									break Next;
								} else {
									continue Next;
								}
							}
						}

						if (rc) {
							// �Ӻ����ҵ���һ������������ȡ����һ��
							result.add(mems.get(i));
							for (--i; i > 0; --i) {
								result.add(mems.get(i));
							}
						} else if (bOne) {
							if (isOrg) {
								this.mems = new ListBase1(1);
								this.mems.add(cur);
								return this;
							} else {
								return cur;
							}
						} else {
							result.add(cur);
						}
					}
				} else {
					Next:
					for (int i = 1; i <= end; ++i) {
						current.setCurrent(i);
						for (int c = 0; c < colCount; ++c) {
							Object flt = fltExps[c].calculate(ctx);
							if (!Variant.isEquals(flt, vals[c])) {
								if (continuous) {
									// ֻ��ǰ����������������
									break Next;
								} else {
									continue Next;
								}
							}
						}

						if (rc) {
							// �ҵ���һ������������ȡ�����
							result.add(mems.get(i));
							for (++i; i <= end; ++i) {
								result.add(mems.get(i));
							}
						} else if (bOne) {
							if (isOrg) {
								this.mems = new ListBase1(1);
								this.mems.add(mems.get(i));
								return this;
							} else {
								return mems.get(i);
							}
						} else {
							result.add(mems.get(i));
						}
					}
				}

				if (bOne) {
					if (isOrg) {
						this.mems = new ListBase1(0);
						return this;
					} else {
						return null;
					}
				} else {
					if (isOrg) {
						this.mems = result.mems;
						return this;
					} else {
						if (returnTable && result.length() == 0) {
							Object obj = ifn();
							if (obj instanceof Record) {
								return new Table(((Record)obj).dataStruct());
							} else {
								return result;
							}
						} else {
							return result;
						}
					}
				}
			}
		} finally {
			stack.pop();
		}
	}

	/**
	 * ���ش����е�n�û���ʹ��n�û��ļ�����Ϊ������
	 * @param exp Expression �������ʽ
	 * @param loc String ����
	 * @param opt String z: ����o���ı�ԭ����
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence sort(Expression exp, String loc, String opt, Context ctx) {
		if (length() == 0) {
			if (this instanceof Table || (opt != null && opt.indexOf('o') != -1)) {
				return this;
			} else {
				return new Sequence(0);
			}
		}
		
		boolean bDesc = false, bOrg = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1) {
				return sort_u(exp, ctx);
			}

			if (opt.indexOf('z') != -1)bDesc = true;
			if (opt.indexOf('o') != -1)bOrg = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
			
			if (opt.indexOf('n') != -1) {
				Sequence seq = group_n(exp, "s", ctx);
				if (bOrg) {
					mems = seq.mems;
					return this;
				} else {
					return seq;
				}
			}
		}

		ListBase1 mems = this.mems;
		int sLength = mems.size();

		Sequence values = calc(exp, opt, ctx);
		ListBase1 valMems = values.mems;
		PSortItem []infos = new PSortItem[sLength + 1];

		for (int i = 1; i <= sLength; ++i) {
			infos[i] = new PSortItem(i, valMems.get(i));
		}

		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = getComparator_0(loc);
		} else {
			comparator = getComparator(loc, true);
		}

		if (bDesc) {
			comparator = new DescComparator(comparator);
		}
		
		comparator = new PSortComparator(comparator);
		MultithreadUtil.sort(infos, 1, infos.length, comparator);
		Sequence result = new Sequence(sLength);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= sLength; ++i) {
			resultMems.add(mems.get(infos[i].index));
		}

		if (bOrg) {
			this.mems = resultMems;
			return this;
		} else {
			return result;
		}
	}
	
	/**
	 * ���ش����е�n�û���ʹ��n�û��ļ�����Ϊ������
	 * @param exps Expression[] �������ʽ
	 * @param loc String ����
	 * @param opt String z: ����o���ı�ԭ����
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence sort(Expression []exps, String loc, String opt, Context ctx) {
		if (length() == 0) {
			if (this instanceof Table || (opt != null && opt.indexOf('o') != -1)) {
				return this;
			} else {
				return new Sequence(0);
			}
		}
		
		boolean bDesc = false, bOrg = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1) {
				return sort_u(exps, ctx);
			}

			if (opt.indexOf('z') != -1)bDesc = true;
			if (opt.indexOf('o') != -1)bOrg = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
		}

		ListBase1 mems = this.mems;
		int sLength = mems.size();

		int fcount = exps.length;
		PSortItem[] infos = new PSortItem[sLength + 1];
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= sLength; ++i) {
				current.setCurrent(i);
				Object []vals = new Object[fcount];
				for (int f = 0; f < fcount; ++f) {
					vals[f] = exps[f].calculate(ctx);
				}
				
				infos[i] = new PSortItem(i, vals);
			}
		} finally {
			stack.pop();
		}

		Comparator<Object> comparator;
		if (loc == null || loc.length() == 0) {
			if (isNullMax) {
				comparator = new ArrayComparator_0(fcount);
			} else {
				comparator = new ArrayComparator(fcount);
			}
		} else {
			Locale locale = parseLocale(loc);
			if (isNullMax) {
				comparator = new LocaleArrayComparator_0(Collator.getInstance(locale), fcount);
			} else {
				comparator = new LocaleArrayComparator(Collator.getInstance(locale), fcount);
			}
		}
		
		if (bDesc) {
			comparator = new DescComparator(comparator);
		}
		
		comparator = new PSortComparator(comparator);
		MultithreadUtil.sort(infos, 1, infos.length, comparator);
		Sequence result = new Sequence(sLength);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= sLength; ++i) {
			resultMems.add(mems.get(infos[i].index));
		}

		if (bOrg) {
			this.mems = resultMems;
			return this;
		} else {
			return result;
		}
	}

	private static void sort(PSortItem[] infos, Sequence[] values,
							 Comparator<Object>[] comparators, int curCol, int start,
							 int end) {
		Comparator<Object> comparator = comparators[curCol];
		MultithreadUtil.sort(infos, start, end, comparator);

		int nextCol = curCol + 1;
		if (nextCol == values.length) {
			return;
		}

		ListBase1 nextMems = values[nextCol].mems;

		// ������һ������
		int prev = start;
		PSortItem prevValue = infos[start];
		for (int i = start + 1; i < end; ++i) {
			if (comparator.compare(prevValue, infos[i]) != 0) {
				if (i - prev > 1) {
					for (int n = prev; n < i; ++n) {
						infos[n].value = nextMems.get(infos[n].index);
					}
					sort(infos, values, comparators, nextCol, prev, i);
				}

				prev = i;
				prevValue = infos[i];
			}
		}

		if (end - prev > 1) {
			for (int n = prev; n < end; ++n) {
				infos[n].value = nextMems.get(infos[n].index);
			}
			
			sort(infos, values, comparators, nextCol, prev, end);
		}
	}

	/**
	 * ���ն����ʽ�Ͷ�˳������
	 * @param exps Expression[] ����ʽ����
	 * @param orders int[] ˳������, 1����, -1����, 0ԭ��
	 * @param loc String ����
	 * @param opt String o���ı�ԭ����
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence sort(Expression[] exps, int[] orders, String loc, String opt, Context ctx) {
		if (length() == 0) {
			if (this instanceof Table || (opt != null && opt.indexOf('o') != -1)) {
				return this;
			} else {
				return new Sequence(0);
			}
		}

		int cols = exps.length;
		boolean bOrg = false, isNullMax = false;
		if (opt != null) {
			if (opt.indexOf('u') != -1) {
				return sort_u(exps, ctx);
			}
			
			if (opt.indexOf('o') != -1)bOrg = true;
			if (opt.indexOf('0') != -1)isNullMax = true;
		}
		
		int zeroCount = 0;
		for (int i = 0; i < cols; ++i) {
			if (orders[i] == 0) {
				zeroCount++;
			}
		}

		if (zeroCount == cols) {
			return this;
		}

		int cmpCount = cols - zeroCount;
		Sequence []values = new Sequence[cmpCount];
		Comparator<Object> comparator;
		if (isNullMax) {
			comparator = getComparator_0(loc);
		} else {
			comparator = getComparator(loc, true);
		}
		
		Comparator<Object>[] comparators = new Comparator[cmpCount];
		Comparator<Object> ascCmp = comparator;
		Comparator<Object> descCmp = new DescComparator(comparator);
		
		ascCmp = new PSortComparator(ascCmp);
		descCmp = new PSortComparator(descCmp);

		// �������еı���ʽ
		for (int i = 0, col = 0; i < cols; ++i) {
			if (orders[i] != 0) {
				values[col] = calc(exps[i], opt, ctx);
				comparators[col] = orders[i] > 0 ? ascCmp : descCmp;
				col++;
			}
		}

		ListBase1 result0 = values[0].mems;
		int size = result0.size();
		PSortItem []infos = new PSortItem[size + 1];
		for (int i = 1; i <= size; ++i) {
			infos[i] = new PSortItem(i, result0.get(i));
		}

		sort(infos, values, comparators, 0, 1, size + 1);

		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;
		ListBase1 mems = this.mems;
		for (int i = 1; i <= size; ++i) {
			resultMems.add(mems.get(infos[i].index));
		}

		if (bOrg) {
			this.mems = resultMems;
			return this;
		} else {
			return result;
		}
	}

	/**
	 * ȡ��λ������
	 * �ȴ�С��������Ȼ��Ѽ�¼�ֳ�seqCount�Σ�ȡ��index�εĵ�һ����¼ֵ
	 * 
	 * @param	seqCount	�ֶε���Ŀ
	 * 				�ֶ���ĿΪ0��ȡ��λ�����ֶ���ĿΪ1ȡ��һ����
	 * @param	index		����λ��, ��1��ʼ�ķֶ�����
	 * 				����λ�ñ�����ڵ���1��С�ڵ���seqCount��
	 * 				������λ�õ���0ʱ����ʾ��ֵΪĬ��ֵ�������������
	 * @return	��������ֵ
	 */
	public Object median(int index, int seqCount) {
		// ����Ϊ�յ����
		if (length() == 0)
			return null;
		if (0 == index && 0 == seqCount) {
			index = 1;
			seqCount = 2;
		}
		// ����У��
		if (0 > index || index > seqCount || seqCount < 2) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("median" + mm.getMessage("function.invalidParam"));
		}
		
		// ����
		Sequence seq = sort(null);
		
		/*
		// ��λ��λλ��
		int pos = 0;
		if (0 == seqCount) {	// �ֶβ���Ϊ0�� ��ʾȡ��λ��
			pos = seq.length();
			if (1 == pos % 2)
				pos = pos / 2 + 1;
			else
				pos = pos / 2;

		} else {
			pos = seq.length() / seqCount;
			pos = pos * (index-1) + 1;
		}
		
		if (0 == pos)
			pos = 1;
		
		// ������ֵ
		return seq.get(pos);
		*/
		
		return seq.median(1, length(), index, seqCount);

	}
	
	/**
	 *  ����һ��Ԫ�ص����е�β�ˣ�������������
	 * @param val Object
	 */
	public void add(Object val) {
		mems.add(val);
	}

	/**
	 * �Ѹ��������е�����Ԫ�����ӵ���ǰ����β�ˣ�������������
	 * @param sequence Sequence
	 */
	public void addAll(Sequence sequence) {
		if (sequence != null) {
			mems.addAll(sequence.mems);
		}
	}

	/**
	 * �Ѷ�Ԫ�����ӵ�����β�ˣ�������������
	 * @param objs Object[]
	 */
	public void addAll(Object []objs) {
		if (objs != null) {
			mems.addAll(objs);
		}
	}

	/**
	 * ɾ��ĳһԪ��
	 * @param index int λ�ã���1��ʼ������С��0��Ӻ���
	 * @return ���ر�ɾ����Ԫ��
	 */
	public Object delete(int index) {
		int oldLen = mems.size();
		if (index > oldLen || index == 0) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
		} else if (index < 0) {
			index += oldLen + 1;
			if (index < 1) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(index - oldLen - 1 + mm.getMessage("engine.indexOutofBound"));
			}
		}
		
		return mems.remove(index);
	}

	/**
	 * ɾ��ָ�������ڵ�Ԫ��
	 * @param from int ��ʼλ�ã�����
	 * @param to int ����λ�ã�����
	 */
	public void delete(int from, int to) {
		if (from < 1 || to < from || to > mems.size()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(from + ":" + to + mm.getMessage("engine.indexOutofBound"));
		}
		mems.removeRange(from, to);
	}

	/**
	 * ��λ��ɾ�����Ԫ��
	 * @param sequence Sequence Ԫ��������Ԫ�ع��ɵ�����
	 * @param opt String n ���ر�ɾ��Ԫ�ع��ɵ�����
	 */
	public Sequence delete(Sequence sequence, String opt) {
		if (sequence == null || sequence.length() == 0) {
			if (opt == null || opt.indexOf('n') == -1) {
				return this;
			} else {
				return new Sequence(0);
			}
		}
		
		int[] index = null;
		try {
			index = sequence.toIntArray();
		} catch (RQException e) {
		}

		if (index == null) {
			Sequence tmp = diff(sequence, false);
			this.mems = tmp.mems;
			if (opt == null || opt.indexOf('n') == -1) {
				return this;
			} else {
				return sequence;
			}
		} else {
			int oldCount = length();
			int delCount = index.length;
			for (int i = 0; i < delCount; ++i) {
				if (index[i] > oldCount || index[i] == 0) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(index[i] + mm.getMessage("engine.indexOutofBound"));
				} else if (index[i] < 0) {
					index[i] += oldCount + 1;
					if (index[i] < 1) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(index[i] - oldCount - 1 + mm.getMessage("engine.indexOutofBound"));
					}
				}
			}

			// ��������������
			Arrays.sort(index);
			
			if (opt == null || opt.indexOf('n') == -1) {
				mems.remove(index);
				return this;
			} else {
				ListBase1 mems = this.mems;
				Sequence result = new Sequence(delCount);
				for (int i = 0; i < delCount; ++i) {
					result.add(mems.get(index[i]));
				}
				
				mems.remove(index);
				return result;
			}
		}
	}
	
	private void deleteNull(boolean emptySeq) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		int nullCount = 0;

		if (emptySeq) {
			for (int i = 1; i <= len; ++i) {
				Sequence seq = (Sequence)mems.get(i);
				if (seq.length() == 0) {
					nullCount++;
				}
			}
			
			if (nullCount == len) {
				this.mems = new ListBase1(1);
			} else if (nullCount > 0) {
				ListBase1 tmp = new ListBase1(len - nullCount);
				for (int i = 1; i <= len; ++i) {
					Sequence seq = (Sequence)mems.get(i);
					if (seq.length() != 0) {
						tmp.add(seq);
					}
				}

				this.mems = tmp;
			}
		} else {
			for (int i = 1; i <= len; ++i) {
				Object obj = mems.get(i);
				if (obj == null) {
					nullCount++;
				}
			}

			if (nullCount == len) {
				this.mems = new ListBase1(1);
			} else if (nullCount > 0) {
				ListBase1 tmp = new ListBase1(len - nullCount);
				for (int i = 1; i <= len; ++i) {
					Object obj = mems.get(i);
					if (obj != null) {
						tmp.add(obj);
					}
				}

				this.mems = tmp;
			}
		}
	}

	// ɾ���ֶ�ֵΪ��/���յļ�¼
	public void deleteNullFieldRecord(int f) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		int nullCount = 0;

		for (int i = 1; i <= len; ++i) {
			Record r = (Record)mems.get(i);
			if (r.getFieldValue(f) == null) {
				nullCount++;
			}
		}

		if (nullCount == len) {
			this.mems = new ListBase1(1);
			rebuildIndexTable();
		} else if (nullCount > 0) {
			ListBase1 tmp = new ListBase1(len - nullCount);
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)mems.get(i);
				if (r.getFieldValue(f) != null) {
					tmp.add(r);
				}
			}

			this.mems = tmp;
			rebuildIndexTable();
		}
	}
	
	// Table�̳��˴˷�����������ɾԪ��ʱ���´�������
	public void rebuildIndexTable() {
	}

	/**
	 * ����ָ�������ڵ�����
	 * @param start int ��ʼλ�ã�������
	 * @param end int ����λ�ã�������
	 */
	public void reserve(int start, int end) {
		int size = mems.size();
		if (start == 0) {
			start = 1;
		} else if (start < 0) {
			start += size + 1;
			if (start < 1) start = 1;
		}

		if (end == 0) {
			end = size;
		} else if (end < 0) {
			end += size + 1;
		} else if (end > size) {
			end = size;
		}

		if (start == 1 && end == size) return;

		if (end < start) {
			mems.clear();
		} else {
			mems.reserve(start, end);
		}
	}

	/**
	 * ��ָ������Ԫ�ط������
	 * @param from int ��ʼλ�ã�����
	 * @param to int ����λ�ã�����
	 * @return Sequence
	 */
	public Sequence split(int from, int to) {
		if (from < 1 || to < from || to > mems.size()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(from + ":" + to + mm.getMessage("engine.indexOutofBound"));
		}

		Sequence seq = new Sequence(to - from + 1);
		ListBase1 retMems = seq.mems;
		ListBase1 mems = this.mems;

		for (int i = from; i <= to; ++i) {
			retMems.add(mems.get(i));
		}

		mems.removeRange(from, to);
		return seq;
	}

	/**
	 * �����д�ָ��λ�ò����������
	 * @param pos λ�ã�����
	 * @return ���غ�벿��Ԫ�ع��ɵ�����
	 */
	public Sequence split(int pos) {
		int len = mems.size();
		Sequence seq = new Sequence(len - pos + 1);
		seq.mems.addSection(mems, pos);
		mems.removeRange(pos, len);
		return seq;
	}
	
	/**
	 * ��ָ��λ�ò���һ������Ԫ��
	 * @param pos int    λ�ã���1��ʼ������0��ʾ׷�ӣ�С��0��Ӻ���
	 * @param val Object ��Ҫ���ӵ�Ԫ�ػ���Ԫ�ع��ɵ�����
	 */
	public void insert(int pos, Object val) {
		ListBase1 mems = this.mems;
		int oldLen = mems.size();
		if (pos == 0) {
			pos = oldLen + 1;
		} else if (pos < 0) {
			pos += oldLen + 1;
			if (pos < 1) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(pos - oldLen - 1 + mm.getMessage("engine.indexOutofBound"));
			}
		} else if (pos > oldLen + 1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		}

		if (val instanceof Sequence) {
			ListBase1 srcMems = ((Sequence)val).mems;
			mems.addAll(pos, srcMems);
		} else {
			mems.add(pos, val);
		}
	}

	/**
	 * ������룬���Ԫ���Ѵ����򲻲���
	 * @param val
	 */
	public void sortedInsert(Object val) {
		if (val instanceof Sequence) {
			ListBase1 mems = this.mems;
			ListBase1 src = ((Sequence)val).mems;
			for (int i = 1, len =src.size(); i <= len; ++i) {
				val = src.get(i);
				int index = mems.binarySearch(val);
				if (index < 0) {
					mems.add(-index, val);
				}
			}
		} else {
			int index = mems.binarySearch(val);
			if (index < 0) {
				mems.add(-index, val);
			}
		}
	}
	
	/**
	 * �޸Ĵ�pos��ʼ��һ������Ԫ��
	 * @param pos int λ�ã���1��ʼ������С��0��Ӻ���
	 * @param val Object һ������Ԫ��
	 * @param opt String n ���ر��޸ĵ�Ԫ�ع��ɵ�����
	 */
	public Object modify(int pos, Object val, String opt) {
		ListBase1 mems = this.mems;
		int oldLen = mems.size();

		if (pos < 0) {
			pos += oldLen + 1;
			if (pos < 1) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(pos - oldLen - 1 + mm.getMessage("engine.indexOutofBound"));
			}
		} else if (pos == 0) {
			pos = oldLen + 1;
		}

		if (val instanceof Sequence) {
			ListBase1 srcMems = ((Sequence)val).mems;
			int srcLen = srcMems.size();
			int endPos = pos + srcLen - 1;

			if (endPos > oldLen) {
				mems.addAll(new Object[endPos - oldLen]);
			}

			if (opt == null || opt.indexOf('n') == -1) {
				for (int i = 1; i <= srcLen; ++i, ++pos) {
					mems.set(pos, srcMems.get(i));
				}
				
				return this;

			} else {
				Sequence result = new Sequence(srcLen);
				for (int i = 1; i <= srcLen; ++i, ++pos) {
					result.add(mems.get(pos));
					mems.set(pos, srcMems.get(i));
				}
				
				return result;
			}
		} else {
			if (pos > oldLen) {
				mems.addAll(new Object[pos - oldLen]);
			}

			if (opt == null || opt.indexOf('n') == -1) {
				mems.set(pos, val);
				return this;
			} else {
				Object old = mems.get(pos);
				mems.set(pos, val);
				return old;
			}
		}
	}

	/**
	 * ��ǰ����Ϊ���У����س���������
	 * @return ��������
	 */
	public int[] toIntArray() {
		ListBase1 mems = this.mems;
		int size = mems.size();
		int[] values = new int[size];

		for (int i = 1, seq = 0; i <= size; ++i, ++seq) {
			Object obj = mems.get(i);
			if (!(obj instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needIntSeries"));
			}

			values[seq] = ((Number)obj).intValue();
		}

		return values;
	}

	/**
	 * �޸�����Ԫ�ص�ֵ��Խλ�Զ���
	 * @param pos int
	 * @param obj Object
	 */
	public void set(int pos, Object obj) {
		if (pos < 1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(pos + mm.getMessage("engine.indexOutofBound"));
		}

		int oldSize = mems.size();
		if (pos > oldSize) {
			mems.addAll(new Object[pos - oldSize]);
		}

		mems.set(pos, obj);
	}

	/**
	 * ����������������ָ�������������Ԫ�أ�����������
	 * @param iseq1 Sequence ��������1
	 * @param iseq2 Sequence ��������2
	 * @return Sequence ����������
	 */
	public Sequence swap(Sequence iseq1, Sequence iseq2) {
		if (iseq1 == null || iseq2 == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("swap" +
								  mm.getMessage("function.paramValNull"));
		}
		if (!iseq1.isIntInterval() || !iseq2.isIntInterval()) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needIntInterval"));
		}

		int length1 = iseq1.length();
		int length2 = iseq2.length();

		ListBase1 mems = this.mems;
		int total = mems.size();

		int s1 = ((Number)iseq1.getMem(1)).intValue();
		int e1 = ((Number)iseq1.getMem(length1)).intValue();

		int s2 = ((Number)iseq2.getMem(1)).intValue();
		int e2 = ((Number)iseq2.getMem(length2)).intValue();

		if (e1 < s1) {
			int temp = e1;
			e1 = s1;
			s1 = temp;
		}
		if (s1 < 1 || e1 > total) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.indexOutofBound"));
		}

		if (e2 < s2) {
			int temp = e2;
			e2 = s2;
			s2 = temp;
		}
		if (s2 < 1 || e2 > total) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.indexOutofBound"));
		}

		// �������[s1:e1]������[s2:e2]�����򽻻�����
		if (s1 > s2) {
			int temp = s1;
			s1 = s2;
			s2 = temp;

			temp = e1;
			e1 = e2;
			e2 = temp;
		}

		if (e1 >= s2) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.areaOverlap"));
		}

		Sequence result = new Sequence(total);
		ListBase1 resultMems = result.mems;

		// ��������1֮ǰ��
		for (int i = 1; i < s1; ++i) {
			resultMems.add(mems.get(i));
		}

		// ��������2��
		for (int i = s2; i <= e2; ++i) {
			resultMems.add(mems.get(i));
		}

		// ��������1������2֮���
		for (int i = e1 + 1; i < s2; ++i) {
			resultMems.add(mems.get(i));
		}

		// ��������1��
		for (int i = s1; i <= e1; ++i) {
			resultMems.add(mems.get(i));
		}

		// ��������2֮���
		for (int i = e2 + 1; i <= total; ++i) {
			resultMems.add(mems.get(i));
		}

		return result;
	}

	/**
	 * ���뵽n���������ѡ��m���뵽n�ı��������Դ���г��ȴ���n�򷵻�Դ����
	 * @param val Object ��Ҫ����Ԫ�ػ�Ԫ�ع��ɵ�����
	 * @param n Integer �����ĳ��Ⱥ󳤶ȵı���
	 * @param opt String m�����뵽n�ı�����l������߲�
	 * @return Sequence
	 */
	public Sequence pad(Object val, int n, String opt) {
		if (n < 1) return this;
		
		int len = mems.size();
		int addCount = 0;
		
		if (opt == null || opt.indexOf('m') == -1) {
			addCount = n - len;
		} else {
			int mod = len % n;
			if (mod == 0) return this;
			
			addCount = n - mod;
		}
		
		if (addCount < 1) {
			return this;
		}
		
		Sequence result = new Sequence(len + addCount);
		if (val instanceof Sequence) {
			Sequence seq = (Sequence)val;
			int count = seq.length();

			if (count > 1) {
				if (opt == null || opt.indexOf('l') == -1) {
					result.addAll(this);
					while (count <= addCount) {
						result.addAll(seq);
						addCount -= count;
					}
					
					if (addCount > 0) {
						result.mems.addAll(seq.mems, addCount);
					}
				} else {
					while (count <= addCount) {
						result.addAll(seq);
						addCount -= count;
					}
					
					if (addCount > 0) {
						result.mems.addAll(seq.mems, addCount);
					}
					
					result.addAll(this);
				}
				
				return result;
			} else if (count == 1) {
				val = seq.getMem(1);
			} // Ԫ�ؿ�ʱ���ӿ����У�
		}

		if (opt == null || opt.indexOf('l') == -1) {
			result.addAll(this);
			for (int i = 0; i < addCount; ++i) {
				result.add(val);
			}
		} else {
			for (int i = 0; i < addCount; ++i) {
				result.add(val);
			}
			
			result.addAll(this);
		}
		
		return result;
	}

	/**
	 * �������ɽ�����ֶ����ƣ����ʡ����name����ݱ���ʽ������
	 * @param exps �������ʽ����
	 * @param names �ֶ������飬���ʡ�����ֶ�������ݱ���ʽ������
	 * @param funcName �������������׳��쳣��Ϣ
	 */
	public void getNewFieldNames(Expression[] exps, String[] names, String funcName) {
		int colCount = exps.length;
		DataStruct ds = null;
		if (length() > 0) {
			Object obj = mems.get(1);
			if (obj instanceof Record) {
				ds = ((Record)obj).dataStruct();
			}
		}
		
		for (int i = 0; i < colCount; ++i) {
			if (names[i] == null || names[i].length() == 0) {
				if (exps[i] == null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(funcName + mm.getMessage("function.invalidParam"));
				}

				names[i] = exps[i].getFieldName(ds);
			} else {
				if (exps[i] == null) {
					exps[i] = Expression.NULL;
				}
			}
		}
	}
	
	/**
	 * ΪԴ����������
	 * @param names String[] ����
	 * @param exps Expression[] �б���ʽ
	 * @param ctx Context
	 */
	public Table newTable(String[] names, Expression[] exps, Context ctx) {
		return newTable(names, exps, null, ctx);
	}
	
	/**
	 * ΪԴ����������
	 * @param names String[] ����
	 * @param exps Expression[] �б���ʽ
	 * @param opt String m�����߳����㣬i������ʽ������Ϊnull�ǲ����ɸ�����¼
	 * @param ctx Context
	 */
	public Table newTable(String[] names, Expression[] exps, String opt, Context ctx) {
		if (names == null || exps == null || names.length != exps.length) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("new" + mm.getMessage("function.invalidParam"));
		}

		getNewFieldNames(exps, names, "new");
		DataStruct ds = new DataStruct(names);
		if (opt == null || opt.indexOf('m') == -1) {
			return newTable(ds, exps, opt, ctx);
		} else {
			return MultithreadUtil.newTable(this, ds, exps, opt, ctx);
		}
	}

	/**
	 * �����д�����һ�������
	 * @param ds ����������ݽṹ
	 * @param exps ��������ֶμ������ʽ����
	 * @param opt i���б���ʽ������Ϊ��ʱ�����ɸ��м�¼
	 * @param ctx ����������
	 * @return �²��������
	 */
	public Table newTable(DataStruct ds, Expression[] exps, String opt, Context ctx) {
		int len = length();
		int colCount = ds.getFieldCount();
		Table table = new Table(ds, len);
		ListBase1 resultMems = table.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current newCurrent = table.new Current();
		stack.push(newCurrent);
		Current current = new Current();
		stack.push(current);

		try {
			if (opt == null || opt.indexOf('i') == -1) {
				for (int i = 1; i <= len; ++i) {
					Record r = new Record(ds);
					resultMems.add(r);
					
					newCurrent.setCurrent(i);
					current.setCurrent(i);
					for (int c = 0; c < colCount; ++c) {
						r.setNormalFieldValue(c, exps[c].calculate(ctx));
					}
				}
			} else {
				Next:
				for (int i = 1, q = 1; i <= len; ++i) {
					Record r = new Record(ds);
					resultMems.add(r);

					newCurrent.setCurrent(q);
					current.setCurrent(i);
					for (int c = 0; c < colCount; ++c) {
						Object obj = exps[c].calculate(ctx);
						if (obj != null) {
							r.setNormalFieldValue(c, obj);
						} else {
							// �������ʽ�����������²����ļ�¼������Ҫ�Ȳ�����¼��������������ɾ����¼
							resultMems.remove(q);
							continue Next;
						}
					}
					
					++q;
				}
			}
		} finally {
			stack.pop();
			stack.pop();
		}

		return table;
	}

	/**
	 * �������е��ֶ�ֵ�ϲ����������
	 * @param gexp ����ֵΪ���еı���ʽ
	 * @param exps ���������ʽ����
	 * @param ds ��������ݽṹ
	 * @param opt 1��gexpΪ��ʱ����һ����¼��Ĭ�ϲ�����
	 * @param ctx ����������
	 * @return ���
	 */
	public Table newTables(Expression gexp, Expression[] exps, DataStruct ds, String opt, Context ctx) {
		int len = length();
		Table result = new Table(ds, len * 2);
		ListBase1 resultMems = result.getMems();
		int fcount = ds.getFieldCount();
		int resultSeq = 1;
		
		boolean isLeft = opt != null && opt.indexOf('1') != -1;
		Sequence ns = null;
		if (isLeft) {
			// ��������������ҳ�����ʽ������X���ֶΣ�����һ����ֵ�ļ�¼��XȡֵΪnullʱ��������¼ѹջ
			ArrayList<String> fieldList = new ArrayList<String>();
			for (Expression exp : exps) {
				exp.getUsedFields(ctx, fieldList);
			}
			
			Object obj = ifn();
			DataStruct oldDs = null;
			if (obj instanceof Record) {
				oldDs = ((Record)obj).dataStruct();
			}
			
			HashSet<String> set = new HashSet<String>();
			for (String name : fieldList) {
				if (oldDs == null || oldDs.getFieldIndex(name) == -1) {
					set.add(name);
				}
			}
			
			ns = new Sequence(1);
			int count = set.size();
			if (count == 0) {
				ns.add(null);
			} else {
				String []names = new String[set.size()];
				set.toArray(names);
				Record nullRecord = new Record(new DataStruct(names));
				ns.add(nullRecord);
			}
		}
		
		// �Ȱ��²��������ѹջ����ֹ���ò���Դ���
		ComputeStack stack = ctx.getComputeStack();
		Current resultCurrent = result.new Current();
		Current current = new Current();
		stack.push(resultCurrent);
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object obj = gexp.calculate(ctx);
				Sequence seq = null;
				
				if (obj instanceof Sequence) {
					seq = (Sequence)obj;
				} else if (obj instanceof Number) {
					int n = ((Number)obj).intValue();
					if (n > 0) {
						seq = new Sequence(1, n);
					}
				} else if (obj instanceof Record) {
					try {
						stack.push((Record)obj);
						resultCurrent.setCurrent(resultSeq);
						Record r = new Record(ds);
						resultMems.add(r);
						resultSeq++;

						for (int f = 0; f < fcount; ++f) {
							r.setNormalFieldValue(f, exps[f].calculate(ctx));
						}
					} finally {
						stack.pop();
					}
					
					continue;
				} else if (obj != null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("news" + mm.getMessage("function.paramTypeError"));
				}
				
				if (seq == null || seq.length() == 0) {
					if (isLeft) {
						seq =ns;
					} else {
						continue;
					}
				}
				
				try {
					Current curCurrent = seq.new Current();
					stack.push(curCurrent);
					int curLen = seq.length();
					
					for (int m = 1; m <= curLen; ++m, ++resultSeq) {
						resultCurrent.setCurrent(resultSeq);
						curCurrent.setCurrent(m);
						Record r = new Record(ds);
						resultMems.add(r);

						for (int f = 0; f < fcount; ++f) {
							r.setNormalFieldValue(f, exps[f].calculate(ctx));
						}
					}
				} finally {
					stack.pop();
				}
			}
		} finally {
			stack.pop();
			stack.pop();
		}

		return result;
	}
	
	/**
	 * �������е��ֶ�ֵ�ϲ����������
	 * @param gexp ����ֵΪ���еı���ʽ
	 * @param names ������ֶ�������
	 * @param exps ���������ʽ����
	 * @param opt 1��gexpΪ��ʱ����һ����¼��Ĭ�ϲ�����
	 * @param ctx ����������
	 * @return ���
	 */
	public Table newTables(Expression gexp, String[] names, Expression[] exps, String opt, Context ctx) {
		if (names == null || exps == null || names.length != exps.length) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("news" + mm.getMessage("function.invalidParam"));
		}

		if (length() > 0) {
			Object val = calc(1, gexp, ctx);
			Sequence seq;
			if (val instanceof Sequence) {
				seq = (Sequence)val;
			} else {
				seq = new Sequence(1);
				seq.add(val);
			}
			
			seq.getNewFieldNames(exps, names, "news");
		} else {
			int colCount = names.length;
			for (int i = 0; i < colCount; ++i) {
				if (names[i] == null || names[i].length() == 0) {
					if (exps[i] == null) {
						MessageManager mm = EngineMessage.get();
						throw new RQException("news" + mm.getMessage("function.invalidParam"));
					}

					names[i] = exps[i].getFieldName();
				} else {
					if (exps[i] == null) {
						exps[i] = Expression.NULL;
					}
				}
			}
		}
		
		DataStruct ds = new DataStruct(names);
		return newTables(gexp, exps, ds, opt, ctx);
	}

	/**
	 * �����е�����ת���������һ����ԱΪ�ֶ������ɵ�����
	 * @return ���
	 */
	public Table toTable() {
		ListBase1 mems = this.mems;
		int len = mems.size();
		
		if (len == 0) {
			return null;
		}
		
		Object obj = mems.get(1);
		if (!(obj instanceof Sequence)) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needSeriesMember"));
		}
		
		Sequence seq = (Sequence)obj;
		int fcount = seq.length();
		String []names = new String[fcount];
		seq.toArray(names);
		Table table = new Table(names, len - 1);
		
		for (int i = 2; i <= len; ++i) {
			obj = mems.get(i);
			if (obj instanceof Sequence) {
				seq = (Sequence)obj;
				int curLen = seq.length();
				if (curLen > fcount) {
					curLen = fcount;
				}
				
				Record r = table.newLast();
				for (int f = 0; f < curLen; ++f) {
					r.setNormalFieldValue(f, seq.getMem(f + 1));
				}
			} else if (obj == null) {
				table.newLast();
			} else {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needSeriesMember"));				
			}
		}
		
		return table;
	}
	
	/**
	 * ���Ƽ�¼���������
	 * @param opt o�������Ƽ�¼
	 * @return
	 */
	public Table derive(String opt) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		DataStruct ds = dataStruct();
		if (ds == null) {
			// �Ե�һ����¼�ĽṹΪ׼
			if (len > 0) {
				Object val = mems.get(1);
				if (val instanceof Record) {
					ds = ((Record)val).dataStruct();
				}
			}
			
			if (ds == null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needPurePmt"));
			} else {
				// �������ݽṹ����ֹ�޸��±����ݽṹ��Ӱ��Դ�ṹ
				ds = ds.dup();
			}
			
			Context ctx = new Context();
			String []names = ds.getFieldNames();
			int fcount = names.length;
			Expression []exps = new Expression[fcount];
			for (int f = 0; f < fcount; ++f) {
				exps[f] = new Expression(ctx, "'" + names[f] + "'");
			}
			
			return newTable(ds, exps, null, ctx);
		}

		// �������ݽṹ����ֹ�޸��±����ݽṹ��Ӱ��Դ�ṹ
		ds = ds.dup();
		Table table = new Table(ds, len);
		if (opt == null || opt.indexOf('o') == -1) {
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)mems.get(i);
				table.newLast(r.getFieldValues());
			}
		} else {
			ListBase1 dest = table.mems;
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)mems.get(i);
				r.setDataStruct(ds);
				dest.add(r);
			}
		}
		
		return table;
	}
	
	/**
	 * ΪԴ����������
	 * @param names String[] ��Ҫ���ӵ���
	 * @param exps Expression[] ��Ҫ���ӵ��еı���ʽ
	 * @param opt String m�����߳����㣬i������ʽ������Ϊnull�ǲ����ɸ�����¼
	 * @param ctx Context
	 */
	public Table derive(String []names, Expression []exps, String opt, Context ctx) {
		if (opt != null && opt.indexOf('m') != -1) {
			return MultithreadUtil.derive(this, names, exps, opt, ctx);
		}
		
		ListBase1 mems = this.mems;
		int len = mems.size();
		DataStruct ds = dataStruct();
		int colCount = exps.length;
		
		if (ds == null) {
			// �Ե�һ����¼�ĽṹΪ׼
			if (len > 0) {
				Object val = mems.get(1);
				if (val instanceof Record) {
					ds = ((Record)val).dataStruct();
				}
			}
			
			if (ds == null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needPurePmt"));
			}
			
			String []srcNames = ds.getFieldNames();
			int srcCount = srcNames.length;
			int totalCount = srcCount + colCount;
			
			String []totalNames = new String[totalCount];
			Expression []totalExps = new Expression[totalCount];
			for (int f = 0; f < srcCount; ++f) {
				totalExps[f] = new Expression(ctx, "~.'" + srcNames[f] + "'");
			}
			
			System.arraycopy(srcNames, 0, totalNames, 0, srcCount);
			System.arraycopy(names, 0, totalNames, srcCount, colCount);
			System.arraycopy(exps, 0, totalExps, srcCount, colCount);
			return newTable(totalNames, totalExps, null, ctx);
		}

		for (int i = 0; i < colCount; ++i) {
			if (names[i] == null || names[i].length() == 0) {
				if (exps[i] == null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("derive" + mm.getMessage("function.invalidParam"));
				}

				names[i] = exps[i].getFieldName(ds);
			} else {
				if (exps[i] == null) {
					exps[i] = Expression.NULL;
				}
			}
		}

		String []oldNames = ds.getFieldNames();
		int oldColCount = oldNames.length;

		// �ϲ��ֶ�
		int newColCount = oldColCount + colCount;
		String []totalNames = new String[newColCount];
		System.arraycopy(oldNames, 0, totalNames, 0, oldColCount);
		System.arraycopy(names, 0, totalNames, oldColCount, colCount);

		// �����м�¼�����ֶΣ��Ա�����ļ�¼��������ǰ���¼���ֶ�
		DataStruct newDs = ds.create(totalNames);
		Table table = new Table(newDs, len);
		ListBase1 resultMems = table.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current newCurrent = table.new Current();
		stack.push(newCurrent);
		Current current = new Current();
		stack.push(current);

		try {
			if (opt == null || opt.indexOf('i') == -1) {
				for (int i = 1; i <= len; ++i) {
					Record r = new Record(newDs);
					resultMems.add(r);
					r.set((Record)mems.get(i));
					
					newCurrent.setCurrent(i);
					current.setCurrent(i);

					// �������ֶ�
					for (int c = 0; c < colCount; ++c) {
						r.setNormalFieldValue(c + oldColCount, exps[c].calculate(ctx));
					}
				}
			} else {
				Next:
				for (int i = 1, q = 1; i <= len; ++i) {
					Record r = new Record(newDs);
					resultMems.add(r);
					r.set((Record)mems.get(i));
					
					newCurrent.setCurrent(q);
					current.setCurrent(i);

					// �������ֶ�
					for (int c = 0; c < colCount; ++c) {
						Object obj = exps[c].calculate(ctx);
						if (obj != null) {
							r.setNormalFieldValue(c + oldColCount, obj);
						} else {
							resultMems.remove(q); // ����exps�����������²����ļ�¼
							continue Next;
						}
					}
					
					++q;
				}
			}
		} finally {
			stack.pop();
			stack.pop();
		}
		
		return table;
	}
	
	/**
	 * ��Դ���е�ָ���ֶ�չ��
	 * @param names ׷�ӵ��ֶ���
	 * @param exps ׷�ӵ��ֶ�ֵ����ʽ
	 * @param opt 
	 * @param ctx
	 * @param level ������ȱʡ2
	 * @return
	 */
	public Table derive(String []names, Expression []exps, String opt, Context ctx, int level) {
		int len = length();
		if (len == 0) {
			return null;
		}
		
		DataStruct ds = dataStruct();
		if (ds == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}
		
		String []srcFieldNames = ds.getFieldNames();
		int fcount = srcFieldNames.length;
		ArrayList<String> nameList = new ArrayList<String>();
		ArrayList<Expression> expList = new ArrayList<Expression>();
		
		for (int f = 0; f < fcount; ++f) {
			Object fval = null;
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)mems.get(i);
				fval = r.getNormalFieldValue(f);
				if (fval != null) {
					break;
				}
			}
			
			String expStr = "#" + (f + 1);
			if (fval instanceof Record) {
				getField((Record)fval, expStr, srcFieldNames[f], 2, level, ctx, nameList, expList);
			} else {
				nameList.add(srcFieldNames[f]);
				Expression exp = new Expression(ctx, expStr);
				expList.add(exp);
			}
		}
		
		fcount = nameList.size();
		int newCount = names != null ? names.length : 0;
		String []totalNames = new String[fcount + newCount];
		Expression []totalExps = new Expression[fcount + newCount];
		
		nameList.toArray(totalNames);
		expList.toArray(totalExps);
		
		if (newCount > 0) {
			System.arraycopy(names, 0, totalNames, fcount, newCount);
			System.arraycopy(exps, 0, totalExps, fcount, newCount);
		}
		
		return newTable(totalNames, totalExps, opt, ctx);
	}
	
	// �ݹ�ȡ�����е���ͨ�ֶ��������ñ���ʽ
	private static void getField(Record r, String prevField, String prevFieldName, int curLevel, int totalLevel, 
			Context ctx, ArrayList<String> nameList, ArrayList<Expression> expList) {
		String []srcFieldNames = r.getFieldNames();
		int fcount = srcFieldNames.length;
		if (curLevel == totalLevel) {
			for (int f = 0; f < fcount; ++f) {
				String expStr = prevField + ".#" + (f + 1);
				if (nameList.contains(srcFieldNames[f])) {
					nameList.add(prevFieldName + '_' +  srcFieldNames[f]);
				} else {
					nameList.add(srcFieldNames[f]);
				}
				
				Expression exp = new Expression(ctx, expStr);
				expList.add(exp);
			}
		} else {
			curLevel++;
			for (int f = 0; f < fcount; ++f) {
				Object fval = r.getNormalFieldValue(f);
				String expStr = prevField + ".#" + (f + 1);
				if (fval instanceof Record) {
					String name = prevFieldName + '_' + srcFieldNames[f];
					getField((Record)fval, expStr, name, curLevel, totalLevel, ctx, nameList, expList);
				} else {
					nameList.add(srcFieldNames[f]);
					Expression exp = new Expression(ctx, expStr);
					expList.add(exp);
				}
			}
		}
	}
	
	/**
	 * ����obj�����ĸ�����
	 * @param obj Object
	 * @param opt String r�������ظ��ķ���
	 * @param ctx Context
	 * @param cs ICellSet
	 * @return Object ���������ĵķ�����Ż�������Ź��ɵ�����
	 */
	public Object penum(Object obj, String opt, Context ctx, ICellSet cs) {
		int len = length();
		boolean isRepeat = opt != null && opt.indexOf('r') != -1;
		Sequence sequence = isRepeat ? new Sequence(2) : null;

		Sequence arg = new Sequence(1);
		arg.add(obj);

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				Object idgVal = getMem(i);
				if (idgVal != null && !(idgVal instanceof String)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("ds.idfTypeError"));
				}

				String temp = (String)idgVal;
				if (temp == null || temp.length() == 0) {
					if (i != len) { // ���һ�����ʽ�ɿ�
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.enumFilterNull"));
					}

					if (isRepeat) {
						if (sequence.length() == 0) {
							sequence.add(i);
						}
						return sequence;
					} else {
						return i;
					}
				} else {
					Object result = evaluate(temp, cs, ctx, arg);
					if (Variant.isTrue(result)) {
						if (isRepeat) {
							sequence.add(i);
						} else {
							return i;
						}
					}
				}
			}
		} finally {
			stack.pop(); // ���г�ջ
		}

		if (isRepeat) {
			if (sequence.length() == 0 && opt != null && opt.indexOf('n') != -1) {
				sequence.add(len + 1);
			}
			
			return sequence;
		} else {
			if (opt == null || opt.indexOf('n') == -1) {
				return null;
			} else {
				return len + 1;
			}
		}
	}

	private Object evaluate(String strExp, ICellSet cs, Context ctx, Sequence arg) {
		Expression exp = new Expression(cs, ctx, strExp);
		ComputeStack stack = ctx.getComputeStack();

		try {
			stack.pushArg(arg);
			return exp.calculate(ctx);
		} finally {
			stack.popArg();
		}
	}

	/**
	 * ��ָ��ö�����ж����н��з���
	 * @param filters Sequence ö�ٱ���ʽ����
	 * @param argExp Expression ���������õ��Ĳ���
	 * @param opt String n��û�ж�Ӧ�ķ��������飬r�������ظ���ö�٣�ȱʡ��Ϊö�ٲ��ظ���p������λ��
	 * @param ctx Context
	 * @param cs ICellSet
	 * @return Sequence
	 */
	public Sequence enumerate(Sequence filters, Expression argExp,
					   String opt, Context ctx, ICellSet cs) {
		if (filters == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("enum" + mm.getMessage("function.paramValNull"));
		}

		boolean notRepeat = true, bPos = false, isNull = false;
		if (opt != null) {
			if (opt.indexOf('r') != -1)notRepeat = false;
			if (opt.indexOf('p') != -1)bPos = true;
			if (opt.indexOf('n') != -1)isNull = true;
		}

		// �����������ua
		ListBase1 filterMems = filters.mems;
		int fsize = filterMems.size();
		Expression[] enumFilter = new Expression[fsize + 2];
		for (int i = 1; i <= fsize; ++i) {
			Object idgVal = filterMems.get(i);
			if (idgVal != null && !(idgVal instanceof String)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("ds.idfTypeError"));
			}

			String temp = (String)idgVal;
			if (temp == null || temp.length() == 0) {
				if (i != fsize) { // ���һ�����ʽ�ɿ�
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.enumFilterNull"));
				}
			} else {
				enumFilter[i] = new Expression(cs, ctx, temp);
			}
		}
		
		if (isNull && enumFilter[fsize] != null) {
			fsize++;
		}

		Sequence result = new Sequence(fsize);
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			ListBase1 selMems = this.mems;
			int selLen = selMems.size();
			Sequence arg = new Sequence(1);
			arg.add(null);

			Sequence[] groups = new Sequence[fsize + 1]; // ������¼
			for (int i = 1; i <= fsize; ++i) {
				groups[i] = new Sequence();
				result.add(groups[i]);
			}

			if (enumFilter[fsize] == null) { // ���һ������������
				for (int i = 1; i <= selLen; ++i) { // Ҫ���з�������е�Ԫ��
					current.setCurrent(i);
					arg.set(1, argExp.calculate(ctx));
					stack.pushArg(arg);
					try {
						boolean bAdd = false;
						for (int s = 1; s < fsize; ++s) { // ��������
							Object value = enumFilter[s].calculate(ctx);
							if (Variant.isTrue(value)) {
								if (!bPos) {
									groups[s].add(selMems.get(i));
								} else {
									groups[s].add(new Integer(i));
								}

								bAdd = true;
								if (notRepeat) {
									break;
								}
							}
						}

						if (!bAdd) {
							if (!bPos) {
								groups[fsize].add(selMems.get(i));
							} else {
								groups[fsize].add(new Integer(i));
							}
						}

					} finally {
						stack.popArg(); // ������ջ
					}
				}
			} else {
				for (int i = 1; i <= selLen; ++i) { // Ҫ���з�������е�Ԫ��
					current.setCurrent(i);
					arg.set(1, argExp.calculate(ctx));
					stack.pushArg(arg);
					try {
						for (int s = 1; s <= fsize; ++s) { // ��������
							Object value = enumFilter[s].calculate(ctx);
							if (Variant.isTrue(value)) {
								if (!bPos) {
									groups[s].add(selMems.get(i));
								} else {
									groups[s].add(new Integer(i));
								}
								if (notRepeat) {
									break;
								}
							}
						}
					} finally {
						stack.popArg(); // ������ջ
					}
				}
			}
		} finally {
			stack.pop(); // ���г�ջ
		}

		return result;
	}

	/**
	 * ����ָ�����н��ж���
	 * @param exp Expression �ȶԱ���ʽ
	 * @param target Sequence Դ����
	 * @param opt String select��ѡ��
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence align(Expression exp, Sequence target, String opt, Context ctx) {
		if (target == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("align" + mm.getMessage("function.paramValNull"));
		}

		boolean isAll = false, isSorted = false, isPos = false, isNull = false, isConj = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1) isAll = true;
			if (opt.indexOf('b') != -1) isSorted = true;
			if (opt.indexOf('p') != -1) isPos = true;
			
			if (opt.indexOf('n') != -1) {
				isAll = true;
				isNull = true;
			}

			if (opt.indexOf('s') != -1) {
				isAll = true;
				isNull = true;
				isConj = true;
			}
		}

		Sequence values = calc(exp, ctx);
		ListBase1 mems = this.mems;
		ListBase1 valMems = values.mems;
		ListBase1 tgtMems = target.mems;
		int valSize = valMems.size();
		int tgtSize = tgtMems.size();

		if (isAll) {
			Sequence other = isNull ? new Sequence() : null;
			Sequence[] retVals = new Sequence[tgtSize];
			for (int i = 0; i < tgtSize; ++i) {
				retVals[i] = new Sequence(4);
			}

			for (int i = 1; i <= valSize; ++i) {
				Object val = valMems.get(i);
				int index;
				if (isSorted) {
					index = tgtMems.firstIndexOf(val, true);
				} else {
					index = tgtMems.firstIndexOf(val);
				}

				if (index > 0) {
					if (isPos) {
						retVals[index - 1].add(new Integer(i));
					} else {
						retVals[index - 1].add(mems.get(i));
					}
				} else if (isNull) {
					if (isPos) {
						other.add(new Integer(i));
					} else {
						other.add(mems.get(i));
					}
				}
			}
			
			Sequence result;
			if (isConj) {
				result = new Sequence(valSize);
				for (int i = 0; i < tgtSize; ++i) {
					result.addAll(retVals[i]);
				}
				
				result.addAll(other);
			} else {
				if (isNull) {
					result = new Sequence(tgtSize + 1);
					result.addAll(retVals);
					result.add(other);
				} else {
					result = new Sequence(retVals);
				}
			}
			return result;
		} else { // ֻѡ���һ������������Ԫ��
			Object[] retVals = new Object[tgtSize];
			for (int i = 1; i <= valSize; ++i) {
				Object val = valMems.get(i);
				int index;
				if (isSorted) {
					index = tgtMems.firstIndexOf(val, true);
				} else {
					index = tgtMems.firstIndexOf(val);
				}

				if (index > 0 && retVals[index - 1] == null) {
					if (isPos) {
						retVals[index - 1] = new Integer(i);
					} else {
						retVals[index - 1] = mems.get(i);
					}
				}
			}
			
			return new Sequence(retVals);
		}
	}

	/**
	 * ����n�����н��ж���
	 * @param exp Expression �ȶԱ���ʽ
	 * @param n ����
	 * @param opt String select��ѡ��
	 * @param ctx Context
	 * @return Sequence
	 */
	public Sequence align(Expression exp, int n, String opt, Context ctx) {
		if (n <= 0) return null;
		
		boolean isAll = false, isRepeat = false, isPos = false;
		if (opt != null) {
			if (opt.indexOf('a') != -1)isAll = true;
			if (opt.indexOf('p') != -1)isPos = true;
			if (opt.indexOf('r') != -1) {
				isAll = true;
				isRepeat = true;
			}
		}

		Sequence values = calc(exp, ctx);
		ListBase1 mems = this.mems;
		ListBase1 valMems = values.mems;
		int valSize = valMems.size();

		if (isAll) {
			Sequence[] resultVals = new Sequence[n];
			for (int i = 0; i < n; ++i) {
				resultVals[i] = new Sequence(4);
			}

			if (isRepeat) { // exp�ķ���ֵΪλ������
				for (int i = 1; i <= valSize; ++i) {
					Object val = valMems.get(i);
					if (val == null) {
						continue;
					} else if (!(val instanceof Sequence)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.needIntSeries"));
					}

					int[] tmp = ((Sequence)val).toIntArray();
					for (int c = 0, count = tmp.length; c < count; ++c) {
						int index = tmp[c];
						if (index > 0 && index <= n) {
							if (isPos) {
								resultVals[index - 1].add(i);
							} else {
								resultVals[index - 1].add(mems.get(i));
							}
						}
					}
				}
			} else {
				for (int i = 1; i <= valSize; ++i) {
					Object val = valMems.get(i);
					if (val == null) {
						continue;
					} else if (!(val instanceof Number)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(mm.getMessage("engine.needIntExp"));
					}

					int index = ((Number)val).intValue();
					if (index > 0 && index <= n) {
						if (isPos) {
							resultVals[index - 1].add(i);
						} else {
							resultVals[index - 1].add(mems.get(i));
						}
					}
				}
			}
			
			return new Sequence(resultVals);
		} else { // ֻѡ���һ������������Ԫ��
			Object[] resultVals = new Object[n];
			for (int i = 1; i <= valSize; ++i) {
				Object val = valMems.get(i);
				if (val == null) {
					continue;
				} else if (!(val instanceof Number)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needIntExp"));
				}

				int index = ((Number)val).intValue();
				if (index > 0 && index <= n && resultVals[index - 1] == null) {
					if (isPos) {
						resultVals[index - 1] = i;
					} else {
						resultVals[index - 1] = mems.get(i);
					}
				}
			}

			return new Sequence(resultVals);
		}
	}

	/**
	 * �����н����������
	 * @param opt String o��ֻ�����ڵĶԱȣ�1: ֻ����ÿ��ĵ�һ��, u�������������h������������@o����
	 * @return Sequence
	 */
	public Sequence group(String opt) {
		boolean removeNull = false, isOrg = false, isNumber = false;
		if (opt != null) {
			if (opt.indexOf('h') != -1) {
				opt = opt.replace('h', 'o');
				return sort(null).group(opt);
			}
			
			if (opt.indexOf('0') != -1) removeNull = true;
			if (opt.indexOf('o') != -1) isOrg = true;
			if (opt.indexOf('n') != -1) isNumber = true;
		}
		
		Sequence seq = this;
		if (removeNull) {
			seq = selectNotNull();
		}

		if (isNumber) {
			return group_n(opt);
		} else if (!isOrg) {
			if (length() > SORT_HASH_LEN) {
				return CursorUtil.hashGroup(seq, opt);
			} else {
				if (opt == null) {
					return seq.sort(null).group("o");
				} else {
					return seq.sort(null).group("o" + opt);
				}
			}
		}

		ListBase1 mems = seq.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;
		Object prev = mems.get(1);

		if (opt.indexOf('1') == -1) {
			Sequence group = new Sequence(7);
			group.add(prev);
			resultMems.add(group);

			for (int i = 2; i <= size; ++i) {
				Object cur = mems.get(i);
				if (Variant.isEquals(prev, cur)) {
					group.add(cur);
				} else {
					// ����
					prev = cur;
					group = new Sequence(7);
					group.add(cur);
					resultMems.add(group);
				}
			}
		} else {
			resultMems.add(prev);
			for (int i = 2; i <= size; ++i) {
				Object cur = mems.get(i);

				if (!Variant.isEquals(prev, cur)) {
					// ����
					prev = cur;
					resultMems.add(cur);
				}
			}
		}

		return result;
	}

	/**
	 * ����exp�����н����������
	 * @param exp Expression ����������ʽ
	 * @param opt String o��ֻ�����ڵĶԱȣ�n��ȡֵΪ������ţ�1: ֻ����ÿ��ĵ�һ��, u�������������i����������ʽ��h������������@o����
	 * p��������Աλ�ã�1: ֻ����ÿ��ĵ�һ��, z������
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence group(Expression exp, String opt, Context ctx) {
		if (opt == null) {
			// #%3���ڰ���#�ı���ʽ�����������ڷ�����������ʽ#������������Ż���
			//if (length() > SORT_HASH_LEN) {
				return CursorUtil.hashGroup(this, new Expression[]{exp}, opt, ctx);
			//} else {
			//	return sort(exp, null, null, ctx).group(exp, "o", ctx);
			//}
		}

		Sequence seq = this;
		if (opt.indexOf('0') != -1) {
			seq = selectNotNull(exp, ctx);
		}

		if (opt.indexOf('h') != -1) {
			opt = opt.replace('h', 'o');
			return sort(exp, null, null, ctx).group(exp, opt, ctx);			
		} else if (opt.indexOf('o') != -1) {
			return seq.group_o(exp, opt, ctx);
		} else if (opt.indexOf('i') != -1) {
			return seq.group_i(exp, ctx);
		} else if (opt.indexOf('n') != -1) {
			return seq.group_n(exp, opt, ctx);
		} else {
			return CursorUtil.hashGroup(seq, new Expression[]{exp}, opt, ctx);
		}
	}
	
	private Sequence group_i(Expression exp, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			Sequence group = new Sequence(7);
			group.add(mems.get(1));
			resultMems.add(group);

			for (int i = 2; i <= size; ++i) {
				current.setCurrent(i);
				if (Variant.isTrue(exp.calculate(ctx))) {
					// ����
					group = new Sequence(7);
					group.add(mems.get(i));
					resultMems.add(group);
				} else {
					group.add(mems.get(i));
				}
			}
		} finally {
			stack.pop();
		}

		return result;
	}
	
	private Sequence group_o(Expression exp, String opt, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Object prevValue;
		Object curValue;
		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			current.setCurrent(1);
			prevValue = exp.calculate(ctx);

			if (opt.indexOf('1') == -1) {
				Sequence group = new Sequence(7);
				group.add(mems.get(1));
				resultMems.add(group);

				for (int i = 2; i <= size; ++i) {
					current.setCurrent(i);
					curValue = exp.calculate(ctx);

					if (Variant.isEquals(prevValue, curValue)) {
						group.add(mems.get(i));
					} else {
						// ����
						prevValue = curValue;
						group = new Sequence(7);
						group.add(mems.get(i));
						resultMems.add(group);
					}
				}
			} else {
				resultMems.add(mems.get(1));
				for (int i = 2; i <= size; ++i) {
					current.setCurrent(i);
					curValue = exp.calculate(ctx);

					if (!Variant.isEquals(prevValue, curValue)) {
						// ����
						prevValue = curValue;
						resultMems.add(mems.get(i));
					}
				}
			}
		} finally {
			stack.pop();
		}

		return result;
	}
	
	private Sequence group_o(Expression []exps, String opt, Context ctx) {
		// �������
		int keyCount = exps.length;
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Object []prevValues = new Object[keyCount];
		Object []curValues = new Object[keyCount];
		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			current.setCurrent(1);
			for (int k = 0; k < keyCount; ++k) {
				prevValues[k] = exps[k].calculate(ctx);
			}

			if (opt.indexOf('1') == -1) {
				Sequence group = new Sequence(7);
				group.add(mems.get(1));
				resultMems.add(group);

				for (int i = 2; i <= size; ++i) {
					current.setCurrent(i);
					for (int k = 0; k < keyCount; ++k) {
						curValues[k] = exps[k].calculate(ctx);
					}

					if (Variant.compareArrays(prevValues, curValues) == 0) {
						group.add(mems.get(i));
					} else {
						// ����
						Object []tmp = prevValues;
						prevValues = curValues;
						curValues = tmp;

						group = new Sequence(7);
						group.add(mems.get(i));
						resultMems.add(group);
					}
				}
			} else {
				resultMems.add(mems.get(1));
				for (int i = 2; i <= size; ++i) {
					current.setCurrent(i);
					for (int k = 0; k < keyCount; ++k) {
						curValues[k] = exps[k].calculate(ctx);
					}

					if (Variant.compareArrays(prevValues, curValues) != 0) {
						// ����
						Object []tmp = prevValues;
						prevValues = curValues;
						curValues = tmp;

						resultMems.add(mems.get(i));
					}
				}

			}
		} finally {
			stack.pop();
		}

		return result;
	}
	
	private Sequence group_n(Expression exp, String opt, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;
		int len = 0;

		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);

		try {
			if (opt.indexOf('1') == -1) {
				for (int i = 1; i <= size; ++i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (!(obj instanceof Number)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException("group: " + mm.getMessage("engine.needIntExp"));
					}

					int index = ((Number)obj).intValue();
					if (index > len) {
						resultMems.ensureCapacity(index);
						for (int j = len; j < index; ++j) {
							resultMems.add(new Sequence(7));
						}

						len = index;
					} else if (index < 1) {
						// ����С��1�ķŹ���Ҫ�ˣ����ٱ��������ֵ��κ�һ����
						continue;
						//MessageManager mm = EngineMessage.get();
						//throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
					}

					Sequence group = (Sequence)resultMems.get(index);
					group.add(mems.get(i));
				}
				
				if (opt.indexOf('s') != -1) {
					result = result.conj(null);
				} else if (opt.indexOf('0') != -1) {
					result.deleteNull(true);
				}
			} else {
				for (int i = 1; i <= size; ++i) {
					current.setCurrent(i);
					Object obj = exp.calculate(ctx);
					if (!(obj instanceof Number)) {
						MessageManager mm = EngineMessage.get();
						throw new RQException("group: " + mm.getMessage("engine.needIntExp"));
					}

					int index = ((Number)obj).intValue();
					if (index > len) {
						resultMems.ensureCapacity(index);
						for (int j = len; j < index; ++j) {
							resultMems.add(null);
						}

						len = index;
					} else if (index < 1) {
						// ����С��1�ķŹ���Ҫ�ˣ����ٱ��������ֵ��κ�һ����
						continue;
					}

					if (resultMems.get(index) == null) {
						resultMems.set(index, mems.get(i));
					}
				}
				
				if (opt.indexOf('0') != -1) {
					result.deleteNull(false);
				}
			}
		} finally {
			stack.pop();
		}

		return result;
	}
	
	private Sequence group_n(String opt) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		if (size == 0) {
			return new Sequence(0);
		}

		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.mems;
		int len = 0;

		if (opt.indexOf('1') == -1) {
			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!(obj instanceof Number)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("group: " + mm.getMessage("engine.needIntExp"));
				}

				int index = ((Number)obj).intValue();
				if (index > len) {
					resultMems.ensureCapacity(index);
					for (int j = len; j < index; ++j) {
						resultMems.add(new Sequence(7));
					}

					len = index;
				} else if (index < 1) {
					// ����С��1�ķŹ���Ҫ�ˣ����ٱ��������ֵ��κ�һ����
					continue;
					//MessageManager mm = EngineMessage.get();
					//throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
				}

				Sequence group = (Sequence)resultMems.get(index);
				group.add(mems.get(i));
			}
			
			if (opt.indexOf('s') != -1) {
				result = result.conj(null);
			} else if (opt.indexOf('0') != -1) {
				result.deleteNull(true);
			}
		} else {
			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!(obj instanceof Number)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("group: " + mm.getMessage("engine.needIntExp"));
				}

				int index = ((Number)obj).intValue();
				if (index > len) {
					resultMems.ensureCapacity(index);
					for (int j = len; j < index; ++j) {
						resultMems.add(null);
					}

					len = index;
				} else if (index < 1) {
					// ����С��1�ķŹ���Ҫ�ˣ����ٱ��������ֵ��κ�һ����
					continue;
					//MessageManager mm = EngineMessage.get();
					//throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
				}

				if (resultMems.get(index) == null) {
					resultMems.set(index, mems.get(i));
				}
			}
			
			if (opt.indexOf('0') != -1) {
				result.deleteNull(false);
			}
		}

		return result;
	}

	/**
	 * ���ݶ����ʽ�����н����������
	 * @param exps Expression[] ����������ʽ
	 * @param opt String o��ֻ�����ڵĶԱȣ�1: ֻ����ÿ��ĵ�һ��, u�������������h������������@o����
	 * @param ctx Context ���������Ļ���
	 * @return Sequence
	 */
	public Sequence group(Expression[] exps, String opt, Context ctx) {
		int keyCount = exps.length;
		if (keyCount == 1) {
			return group(exps[0], opt, ctx);
		}

		if (opt == null) {
			return CursorUtil.hashGroup(this, exps, opt, ctx);
		} else if(opt.indexOf('h') != -1) {
			opt = opt.replace('h', 'o');
			return sort(exps, null, null, ctx).group(exps, opt, ctx);			
		} else if(opt.indexOf('o') != -1) {
			return group_o(exps, opt, ctx);
		} else if (opt.indexOf('s') != -1) {
			int []orders = new int[keyCount];
			for (int i = 0; i < keyCount; ++i) {
				orders[i] = 1;
			}
			
			return sort(exps, orders, null, null, ctx);
		} else {
			return CursorUtil.hashGroup(this, exps, opt, ctx);
		}
	}

	/**
	 * ����ͳ�ƣ�������ֵ�ͻ���ֵ���ɵ����
	 * @param exps Expression[] �������ʽ
	 * @param names String[] �����ֶ��ڽ������е��ֶ���
	 * @param calcExps Expression[] ���ܱ���ʽ���﷨~.f����
	 * @param calcNames String[] �����ֶ��ڽ������е��ֶ���
	 * @param opt String o��ֻ�����ڵĶԱȣ�n���������ʽȡֵΪ��ţ�u�������������b�������ȥ�������ֶ�
	 * @param ctx Context
	 * @return Table
	 */
	public Table group(Expression[] exps, String[] names, Expression[] calcExps, 
			String[] calcNames, String opt, Context ctx) {
		if (length() == 0) {
			if (opt == null || opt.indexOf('t') == -1) {
				return null;
			} else {
				boolean needGroupField = opt == null || opt.indexOf('b') == -1;
				int keyCount = needGroupField && exps != null ? exps.length : 0;
				int valCount = calcExps != null ? calcExps.length : 0;
				Expression []totalExps = new Expression[keyCount + valCount];
				String []totalNames = new String[keyCount + valCount];
				if (keyCount > 0) {
					System.arraycopy(exps, 0, totalExps, 0, keyCount);
					System.arraycopy(names, 0, totalNames, 0, keyCount);
				}
				
				if (valCount > 0) {
					System.arraycopy(calcExps, 0, totalExps, keyCount, valCount);
					System.arraycopy(calcNames, 0, totalNames, keyCount, valCount);
				}
				
				getNewFieldNames(totalExps, totalNames, "group");
				DataStruct ds = new DataStruct(totalNames);
				if (keyCount > 0) {
					String []keyNames = new String[keyCount];
					System.arraycopy(totalNames, 0, keyNames, 0, keyCount);
					ds.setPrimary(keyNames);
				}
				
				return new Table(ds);
			}
		}

		if (exps == null || exps.length == 0) {
			Sequence seq = new Sequence(1);
			seq.add(this);
			return seq.newTable(calcNames, calcExps, ctx);
		}
		
		if (calcExps == null || calcExps.length == 0) {
			return groups(exps, names, null, null, opt, ctx);
		}

		Sequence groups = group(exps, opt, ctx);
		
		// ��������������ֶ�
		if (opt != null && opt.indexOf('b') != -1) {
			return groups.newTable(calcNames, calcExps, ctx);
		}
		
		int keyCount = exps.length;
		int valCount = calcExps.length;
		Expression []totalExps = new Expression[keyCount + valCount];
		String []totalNames = new String[keyCount + valCount];
		System.arraycopy(exps, 0, totalExps, 0, keyCount);
		System.arraycopy(calcExps, 0, totalExps, keyCount, valCount);
		System.arraycopy(names, 0, totalNames, 0, keyCount);
		System.arraycopy(calcNames, 0, totalNames, keyCount, valCount);
		
		getNewFieldNames(totalExps, totalNames, "group");
		int len = groups.length();
		Sequence keyGroups = new Sequence(len);
		for (int i = 1; i <= len; ++i) {
			Sequence seq = (Sequence)groups.getMem(i);
			keyGroups.add(seq.getMem(1));
		}
		
		Table result = new Table(totalNames, len);
		ComputeStack stack = ctx.getComputeStack();
		Current current = keyGroups.new Current();
		stack.push(current);

		// ��������ֶ�ֵ
		try {
			for (int i = 1; i <= len; ++i) {
				Record r = result.newLast();
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					r.setNormalFieldValue(c, exps[c].calculate(ctx));
				}
			}
		} finally {
			stack.pop();
		}
		
		current = groups.new Current();
		stack.push(current);

		// ����ۺ��ֶ�ֵ
		try {
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)result.getMem(i);
				current.setCurrent(i);
				for (int c = 0; c < valCount; ++c) {
					r.setNormalFieldValue(c + keyCount, calcExps[c].calculate(ctx));
				}
			}
		} finally {
			stack.pop();
		}
		
		String []keyNames = new String[keyCount];
		System.arraycopy(totalNames, 0, keyNames, 0, keyCount);
		result.setPrimary(keyNames);
		return result;
	}
	
	public static Node[] prepareGatherMethods(Expression[] exps, Context ctx) {
		if (exps == null) return null;

		int count = exps.length;
		Node []gathers = new Node[count];
		for (int i = 0; i < count; ++i) {
			Node home = exps[i].getHome();
			//if (!(home instanceof Gather)) {
			//	MessageManager mm = EngineMessage.get();
			//	throw new RQException(exps[i].toString() + mm.getMessage("engine.unknownGroupsMethod"));
			//}

			gathers[i] = home;
			gathers[i].prepare(ctx);
		}

		return gathers;
	}
	
	public static void prepareGatherMethods(Node[] gathers, Context ctx) {
		if (gathers == null)
			return;

		int count = gathers.length;
		//Gather []gathers = new Gather[count];
		for (int i = 0; i < count; ++i) {
			//Node home = exps[i].getHome();
			//if (!(home instanceof Gather)) {
			//	MessageManager mm = EngineMessage.get();
			//	throw new RQException(exps[i].toString() + mm.getMessage("engine.unknownGroupsMethod"));
			//}

			//gathers[i] = (Gather)home;
			gathers[i].prepare(ctx);
		}

		//return gathers;
	}

	/**
	 * ���������������ã���������avg���������ֵ
	 * @param gathers ���ܱ���ʽ
	 */
	public void finishGather(Node[]gathers) {
		if (gathers == null || length() == 0) return;
		
		int valCount = gathers.length;
		boolean []signs = new boolean[valCount];
		boolean sign = false;
		for (int i = 0; i < valCount; ++i) {
			signs[i] = gathers[i].needFinish();
			if (signs[i]) {
				sign = true;
			}
		}
		
		if (!sign) return;
		
		Record r = (Record)getMem(1);
		int keyCount = r.getFieldCount() - valCount;
		ListBase1 mems = this.mems;
		int len = mems.size();
		
		for (int i = 1; i <= len; ++i) {
			r = (Record)mems.get(i);
			for (int v = 0, f = keyCount; v < valCount; ++v, ++f) {
				if (signs[v]) {
					Object val = gathers[v].finish(r.getNormalFieldValue(f));
					r.setNormalFieldValue(f, val);
				}
			}
		}
	}
	
	/**
	 * ���̷߳��������һ�λ��ܽ��������
	 * @param gathers ���ܱ���ʽ
	 */
	public void finishGather1(Node[]gathers) {
		if (gathers == null || length() == 0) return;
		
		int valCount = gathers.length;
		boolean []signs = new boolean[valCount];
		boolean sign = false;
		for (int i = 0; i < valCount; ++i) {
			signs[i] = gathers[i].needFinish1();
			if (signs[i]) {
				sign = true;
			}
		}
		
		if (!sign) return;
		
		Record r = (Record)getMem(1);
		int keyCount = r.getFieldCount() - valCount;
		ListBase1 mems = this.mems;
		int len = mems.size();
		
		for (int i = 1; i <= len; ++i) {
			r = (Record)mems.get(i);
			for (int v = 0, f = keyCount; v < valCount; ++v, ++f) {
				if (signs[v]) {
					Object val = gathers[v].finish1(r.getNormalFieldValue(f));
					r.setNormalFieldValue(f, val);
				}
			}
		}
	}
	
	public void shift(int pos, int move) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		int end = size - move;
		
		for (; pos <= end; ++pos) {
			mems.set(pos, mems.get(pos + move));
		}
		
		/*for (int i = end + 1; i <= size; ++i) {
			mems.set(i, null);
		}*/
	}
	
	/**
	 * ȡ������¼�����ݽṹ�������һ��Ԫ�ز��Ǽ�¼�򷵻�null
	 * @return ��¼�����ݽṹ
	 */
	public DataStruct getFirstRecordDataStruct() {
		if (mems.size() > 0) {
			Object obj = mems.get(1);
			if (obj instanceof Record) {
				return ((Record)obj).dataStruct();
			}
		}
		
		return null;
	}
	
	/**
	 * ����������ת��ת��������
	 * @param gexps �������ʽ����
	 * @param gnames �����ֶ�������
	 * @param fexp �����ֶ�
	 * @param vexp ȡֵ�ֶεĻ��ܱ���ʽ
	 * @param nexps ����ֵ
	 * @param nameObjects ������ֶ���
	 * @param ctx ����������
	 * @return ���
	 */
	public Table pivot_s(Expression[] gexps, String []gnames, Expression fexp, Expression vexp, 
			Expression []nexps, Object []nameObjects, Context ctx) {
		if (length() == 0) return null;
		
		int nullIndex = -1; // ʡ����Ni�򵱳���������
		Object []vals;
		String []names;
		if (nexps == null) {
			Sequence seq = calc(fexp, ctx).id(null);
			vals = seq.toArray();
			int count = vals.length;
			names = new String[count];
			for (int i = 0; i < count; ++i) {
				names[i] = Variant.toString(vals[i]);
			}
		} else {
			int count = nexps.length;
			vals = new Object[count];
			names = new String[count];
			for (int i = 0; i < count; ++i) {
				if (nexps[i] == null) {
					if (nullIndex == -1) {
						nullIndex = i;
					} else {
						MessageManager mm = EngineMessage.get();
						throw new RQException("pivot" + mm.getMessage("function.invalidParam"));
					}
					
					if (nameObjects[i] != null) {
						names[i] = Variant.toString(nameObjects[i]);
					}
				} else {
					vals[i] = nexps[i].calculate(ctx);
					if (nameObjects[i] == null) {
						names[i] = Variant.toString(vals[i]);
					} else {
						names[i] = Variant.toString(nameObjects[i]);
					}
				}
			}
		}
		
		Node home = vexp.getHome();
		if (!(home instanceof Gather)) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pivot" + mm.getMessage("function.invalidParam"));
		}
		
		Gather gather = (Gather)home;
		int keyCount = gexps == null ? 0 : gexps.length;
		int ncount = names.length;
		int totalCount = keyCount + ncount;
		String []totalNames = new String[totalCount];
		System.arraycopy(names, 0, totalNames, keyCount, ncount);
		
		DataStruct ds = getFirstRecordDataStruct();
		for (int i = 0; i < keyCount; ++i) {
			if (gnames != null && gnames[i] != null) {
				totalNames[i] = gnames[i];
			} else {
				totalNames[i] = gexps[i].getFieldName(ds);
			}
		}
		
		Sequence groups;
		if (keyCount > 0) {
			groups = group(gexps, null, ctx);
		} else {
			groups = new Sequence(1);
			groups.add(this);
		}
		
		int len = groups.length();
		Table result = new Table(totalNames, len);
		
		ComputeStack stack = ctx.getComputeStack();
		for (int i = 1; i <= len; ++i) {
			Sequence group = (Sequence)groups.getMem(i);
			Record r = result.newLast();
			
			Current current = group.new Current();
			stack.push(current);

			try {
				current.setCurrent(1);
				for (int f = 0; f < keyCount; ++f) {
					r.setNormalFieldValue(f, gexps[f].calculate(ctx));
				}
				
				for (int f = keyCount; f < totalCount; ++f) {
					r.setNormalFieldValue(f, new Sequence());
				}
				
				Next:
				for (int m = 1, size = group.length(); m <= size; ++m) {
					current.setCurrent(m);
					Object fval = fexp.calculate(ctx);
					for (int n = 0; n < ncount; ++n) {
						if (n != nullIndex && Variant.isEquals(fval, vals[n])) {
							Sequence seq = (Sequence)r.getNormalFieldValue(keyCount + n);
							seq.add(vexp.calculate(ctx));
							continue Next;
						}
					}
					
					if (nullIndex != -1) {
						Sequence seq = (Sequence)r.getNormalFieldValue(keyCount + nullIndex);
						seq.add(vexp.calculate(ctx));
					}
				}
			} finally {
				stack.pop();
			}
			
			for (int n = 0; n < ncount; ++n) {
				Sequence seq = (Sequence)r.getNormalFieldValue(keyCount + n);
				r.setNormalFieldValue(keyCount + n, gather.gather(seq));
			}
		}
		
		return result;
	}
	
	/**
	 * ����������ת��ת��
	 * @param gexps �������ʽ����
	 * @param gnames �����ֶ�������
	 * @param fexp �����ֶ�
	 * @param vexp ȡֵ�ֶ�
	 * @param nexps ����ֵ
	 * @param nameObjects ������ֶ���
	 * @param ctx ����������
	 * @return ���
	 */
	public Table pivot(Expression[] gexps, String []gnames, Expression fexp, Expression vexp, 
			Expression []nexps, Object []nameObjects, Context ctx) {
		if (length() == 0) return null;
		
		Object []vals;
		String []names;
		if (nexps == null) {
			Sequence seq = calc(fexp, ctx).id(null);
			vals = seq.toArray();
			int count = vals.length;
			names = new String[count];
			for (int i = 0; i < count; ++i) {
				names[i] = Variant.toString(vals[i]);
			}
		} else {
			int count = nexps.length;
			vals = new Object[count];
			names = new String[count];
			for (int i = 0; i < count; ++i) {
				if (nexps[i] == null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("pivot" + mm.getMessage("function.invalidParam"));					
				}
				
				vals[i] = nexps[i].calculate(ctx);
				if (nameObjects[i] == null) {
					names[i] = Variant.toString(vals[i]);
				} else {
					names[i] = Variant.toString(nameObjects[i]);
				}
			}
		}
		
		int ncount = names.length;
		int keyCount = gexps == null ? 0 : gexps.length;
		String []totalNames = new String[keyCount + ncount];
		System.arraycopy(names, 0, totalNames, keyCount, ncount);
		
		DataStruct ds = getFirstRecordDataStruct();
		for (int i = 0; i < keyCount; ++i) {
			if (gnames != null && gnames[i] != null) {
				totalNames[i] = gnames[i];
			} else {
				totalNames[i] = gexps[i].getFieldName(ds);
			}
		}
		
		Sequence groups;
		if (keyCount > 0) {
			groups = group(gexps, null, ctx);
		} else {
			groups = new Sequence(1);
			groups.add(this);
		}
		
		int len = groups.length();
		Table result = new Table(totalNames, len);
		
		ComputeStack stack = ctx.getComputeStack();
		for (int i = 1; i <= len; ++i) {
			Sequence group = (Sequence)groups.getMem(i);
			Record r = result.newLast();
			
			Current current = group.new Current();
			stack.push(current);

			try {
				current.setCurrent(1);
				for (int f = 0; f < keyCount; ++f) {
					r.setNormalFieldValue(f, gexps[f].calculate(ctx));
				}
				
				for (int m = 1, size = group.length(); m <= size; ++m) {
					current.setCurrent(m);
					Object fval = fexp.calculate(ctx);
					for (int n = 0; n < ncount; ++n) {
						if (Variant.isEquals(fval, vals[n])) {
							r.setNormalFieldValue(keyCount + n, vexp.calculate(ctx));
							break;
						}
					}
				}
			} finally {
				stack.pop();
			}
		}
		
		return result;
	}
	
	/**
	 * ����������ת��ת��
	 * @param gexps �������ʽ����
	 * @param gnames �����ֶ�������
	 * @param fname ����������ֶ���
	 * @param vname �����ֵ�ֶ���
	 * @param nexps ����ֵ����ʽ
	 * @param nameObjects ���������ֵ����ʽ
	 * @param ctx ����������
	 * @return ���
	 */
	public Table unpivot(Expression[] gexps, String []gnames, String fname, String vname, 
			Expression []nexps, Object []nameObjects, Context ctx) {
		int len = length();
		if (len == 0) return null;

		int keyCount = gexps == null ? 0 : gexps.length;
		DataStruct ds = getFirstRecordDataStruct();
		
		if (nexps == null) {
			int fcount = ds.getFieldCount();
			boolean []signs = new boolean[fcount];
			int ncount = fcount;
			
			for (int i = 0; i < keyCount; ++i) {
				String name = gexps[i].getFieldName(ds);
				int index = ds.getFieldIndex(name);
				if (index != -1 && !signs[index]) {
					signs[index] = true;
					ncount--;
				}
			}
			
			nexps = new Expression[ncount];
			nameObjects = new String[ncount];
			for (int i = 0, seq = 0; i < fcount; ++i) {
				if (!signs[i]) {
					String str = ds.getFieldName(i);
					nexps[seq] = new Expression("#" + (i + 1));
					nameObjects[seq] = str;
					seq++;
				}
			}
		} else {
			for (int i = 0; i < nexps.length; ++i) {
				if (nexps[i] == null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException("pivot" + mm.getMessage("function.invalidParam"));					
				}
			}
		}
		
		String []totalNames = new String[keyCount + 2];
		totalNames[keyCount] = fname;
		totalNames[keyCount + 1] = vname;
		for (int i = 0; i < keyCount; ++i) {
			if (gnames != null && gnames[i] != null) {
				totalNames[i] = gnames[i];
			} else {
				totalNames[i] = gexps[i].getFieldName(ds);
			}
		}
		
		int ncount = nexps.length;
		Object []names = new Object[ncount];
		for (int i = 0; i < ncount; ++i) {
			if (nameObjects[i] == null) {
				names[i] =  nexps[i].getFieldName(ds);
			} else {
				names[i] = nameObjects[i];
			}
		}

		Object []keys = new Object[keyCount];
		Table result = new Table(totalNames, len * ncount);
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);
		
		try {
			for (int i = 1; i <= len; ++i) {
				current.setCurrent(i);
				for (int k = 0; k < keyCount; ++k) {
					keys[k] = gexps[k].calculate(ctx);
				}
				
				for (int n = 0; n < ncount; ++n) {
					Record r = result.newLast(keys);
					r.setNormalFieldValue(keyCount, names[n]);
					r.setNormalFieldValue(keyCount + 1, nexps[n].calculate(ctx));
				}
			}
		} finally {
			stack.pop();
		}
		
		return result;
	}
	
	/**
	 * ����ͳ��
	 * @param exps Expression[] �������ʽ
	 * @param names String[] �����ֶ��ڽ������е��ֶ���
	 * @param calcExps Expression[] ���ܱ���ʽ
	 * @param calcNames String[] �����ֶ��ڽ������е��ֶ���
	 * @param opt String o��ֻ�����ڵĶԱȣ�n���������ʽȡֵΪ��ţ�u�������������h������������@o����
	 * @param ctx Context
	 * @return Table
	 */
	public Table groups(Expression[] exps, String[] names, Expression[] calcExps,
						String[] calcNames, String opt, Context ctx) {
		int len = length();
		if (len < 1) {
			if (opt == null || opt.indexOf('t') == -1) {
				return null;
			} else {
				int keyCount = exps != null ? exps.length : 0;
				int valCount = calcExps != null ? calcExps.length : 0;
				Expression []totalExps = new Expression[keyCount + valCount];
				String []totalNames = new String[keyCount + valCount];
				if (keyCount > 0) {
					System.arraycopy(exps, 0, totalExps, 0, keyCount);
					System.arraycopy(names, 0, totalNames, 0, keyCount);
				}
				
				if (valCount > 0) {
					System.arraycopy(calcExps, 0, totalExps, keyCount, valCount);
					System.arraycopy(calcNames, 0, totalNames, keyCount, valCount);
				}
				
				getNewFieldNames(totalExps, totalNames, "group");
				DataStruct ds = new DataStruct(totalNames);
				if (keyCount > 0) {
					String []keyNames = new String[keyCount];
					System.arraycopy(totalNames, 0, keyNames, 0, keyCount);
					ds.setPrimary(keyNames);
				}
				
				return new Table(ds);
			}
		}
		
		// #%3���ڰ���#�ı���ʽ�����������ڷ�����������ʽ#������������Ż���
		//if (opt == null && len <= SORT_HASH_LEN && exps != null) {
			//int keyCount = exps.length;
			//int []orders = new int[keyCount];
			//for (int i = 0; i < keyCount; ++i) {
			//	orders[i] = 1;
			//}
			
			//Sequence seq = sort(exps, orders, null, null, ctx);
			//GroupsResult groups = new GroupsResult(exps, names, calcExps, calcNames, "o", ctx);
			//groups.push(seq, ctx);
			//return groups.getResultTable();
		//} else {
			IGroupsResult groups = IGroupsResult.instance(exps, names, calcExps, calcNames, opt, ctx, len / 2);
			groups.push(this, ctx);
			return groups.getResultTable();
		//}
	}
	
	/**
	 * Ϊ����������У�������Di,...Ϊ�е������A.group(D1).(~.group(D2).(��(~.id(Di))))
	 * @param gexps
	 * @param opt o���ٶ�������
	 * @param ctx
	 * @return
	 */
	public Table groupi(Expression []gexps, String opt, Context ctx) {
		int fcount = gexps.length;
		String []names = new String[fcount];
		
		Table table = newTable(names, gexps, ctx);
		int []colIndex = new int[fcount];
		for (int i = 0; i < fcount; ++i) {
			colIndex[i] = i;
		}
		
		if (opt == null || opt.indexOf('o') == -1) {
			// ������ά���򣬺�����group@o����
			table.sortFields(colIndex);
		}
		
		Expression fexp = new Expression(ctx, "#1");
		Sequence group = ((Sequence)table).group_o(fexp, "o", ctx);
		
		int len = group.length();
		Table result = new Table(table.dataStruct(), len);
		for (int i = 1; i <= len; ++i) {
			Sequence curGroup = (Sequence)group.getMem(i);
			Record r = result.newLast();
			Record sr = (Record)curGroup.getMem(1);
			r.setNormalFieldValue(0, sr.getNormalFieldValue(0));
			
			if (fcount == 1) {
				continue;
			}
			
			fexp = new Expression(ctx, "#2");
			curGroup = curGroup.group_o(fexp, "o", ctx);
			r.setNormalFieldValue(1, curGroup.calc(fexp, ctx));
			
			// �������µı���ʽ���м���
			//curGroup = curGroup.(~.group@o(#3))		curGroup.(~.(#3))
			//curGroup = curGroup.(~.(~.group@o(#4)))	curGroup.(~.(~.(#4)))			
			for (int f = 3; f <= fcount; ++f) {
				String gstr = "~.group@o(#" + f + ")";
				String vstr = "~.(#" + f + ")";
				fexp = new Expression(ctx, "#" + f);
				
				for (int n = 3; n < f; ++n) {
					gstr = "~.(" + gstr + ")";
					vstr = "~.(" + vstr + ")";
				}
				
				curGroup = curGroup.calc(new Expression(ctx, gstr), ctx);
				Sequence curVal = curGroup.calc(new Expression(ctx, vstr), ctx);
				r.setNormalFieldValue(f - 1, curVal);
			}
		}
		
		return result;
	}
	
	//-------------------------------------join start-----------------------------------------------

	/**
	 * �������
	 * @param sequences ����˵���������
	 * @param names ������ֶ�������
	 * @return Table
	 */
	public static Table cross(Sequence[] sequences, String[] names) {
		if (sequences == null || sequences.length < 2) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("cross" + mm.getMessage("function.invalidParam"));
		}

		// ����Դ����join������ļ�¼��Ŀ
		int newLen = 1;
		int count = sequences.length;
		for (int i = 0; i < count; ++i) {
			newLen *= sequences[i].length();
			if (newLen == 0) {
				return new Table(names, 0);
			}
		}

		// �������еļ�¼
		Table table = new Table(names, newLen);
		Record[] rs = new Record[newLen];
		for (int i = 0; i < newLen; ++i) {
			rs[i] = table.newLast();
		}

		// �ֱ��ÿһ����¼��ÿ���ֶθ�ֵ
		int repeat = 1; // ��ǰ�ֶ��ظ�����Ŀ
		for (int field = count - 1; field >= 0; --field) {
			ListBase1 subMems = sequences[field].mems;
			int subCount = subMems.size();
			int index = 0;
			
			while (index < newLen) {
				for (int i = 1; i <= subCount; ++i) {
					Object val = subMems.get(i);
					for (int j = 0; j < repeat; ++j) {
						rs[index++].setNormalFieldValue(field, val);
					}
				}
			}
			
			repeat *= subCount;
		}

		return table;
	}

	/**
	 * ������е����У������ֶεļ���������ǰ���ֶε�ֵ
	 * @param sequences Դ��������
	 * @param fltExps ���˱���ʽ����
	 * @param fltOpts select������ѡ��
	 * @param names ������ֶ�������
	 * @param opt ѡ�1��������
	 * @param ctx ����������
	 * @return Table
	 */
	static public Table xjoin(Sequence[] sequences, Expression[] fltExps,
							  String[] fltOpts, String[] names, String opt, Context ctx) {
		if (sequences == null || sequences.length < 2) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("xjoin" + mm.getMessage("function.invalidParam"));
		}

		int count = sequences.length;
		if (names == null) {
			names = new String[count];
		} else {
			if (names.length != count) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("xjoin" + mm.getMessage("function.invalidParam"));
			}
		}

		if (fltExps == null) {
			fltExps = new Expression[count];
		} else {
			if (fltExps.length != count) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("xjoin" + mm.getMessage("function.invalidParam"));
			}
		}

		if (fltOpts == null) {
			fltOpts = new String[count];
		} else {
			if (fltOpts.length != count) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("xjoin" + mm.getMessage("function.invalidParam"));
			}
		}

		if (sequences[0] == null) {
			return new Table(names);
		}
		
		int len = sequences[0].length();
		if (len < 512) {
			len = 1024;
		} else {
			len *= 2;
		}

		Table result = new Table(names, len);
		Table tmp = new Table(result.dataStruct(), 1);
		boolean isLeft = opt != null && opt.indexOf('1') != -1;
		
		// ����һ����ǰ��¼�������ֶεļ����������ǰ���ֶε�ֵ
		Record newCur = tmp.newLast();
		ComputeStack stack = ctx.getComputeStack();
		stack.push(newCur);
		try {
			xjoin(sequences, fltExps, fltOpts, 0, newCur, result, isLeft, ctx);
		} finally {
			stack.pop();
		}

		return result;
	}

	private static void xjoin(Sequence[] sequences, Expression[] fltExps,
							  String[] fltOpts, int col, Record newCur, 
							  Table retTable, boolean isLeft, Context ctx) {
		Sequence sequence = sequences[col];
		Object value = null;
		if (fltExps[col] != null && sequence != null) {
			Object obj = sequence.select(fltExps[col], fltOpts[col], ctx);
			if (obj instanceof Sequence) {
				sequence = (Sequence)obj;
			} else {
				value = obj;
				sequence = null;
			}
		}

		if (sequence != null && sequence.length() > 0) {
			ListBase1 colMems = sequence.mems;
			int length = colMems.size();
			if (col == sequences.length - 1) {
				for (int i = 1; i <= length; ++i) {
					newCur.setNormalFieldValue(col, colMems.get(i));
					Record r = retTable.newLast();
					r.set(newCur);
				}
			} else {
				int nextCol = col + 1;
				for (int i = 1; i <= length; ++i) {
					newCur.setNormalFieldValue(col, colMems.get(i));
					xjoin(sequences, fltExps, fltOpts, nextCol, newCur, retTable, isLeft, ctx);
				}
			}
		} else if (value != null) {
			newCur.setNormalFieldValue(col, value);
			if (col == sequences.length - 1) {
				Record r = retTable.newLast();
				r.set(newCur);
			} else {
				xjoin(sequences, fltExps, fltOpts, col + 1, newCur, retTable, isLeft, ctx);
			}
		} else {
			if (isLeft && col > 0) {
				newCur.setNormalFieldValue(col, null);
				if (col == sequences.length - 1) {
					Record r = retTable.newLast();
					r.set(newCur);
				} else {
					xjoin(sequences, fltExps, fltOpts, col + 1, newCur, retTable, isLeft, ctx);
				}
			}
		}
	}

	/**
	 * ��λ�ð�ָ��������������
	 * @param sequences ��������
	 * @param names ������ֶ�������
	 * @param opt ѡ�1�������ӣ�f��ȫ����
	 * @return
	 */
	static public Table pjoin(Sequence[] sequences, String[] names, String opt) {
		if (sequences == null || sequences.length < 2) {
			MessageManager mm = EngineMessage.get();
			throw new RQException("pjoin" + mm.getMessage("function.invalidParam"));
		}

		int count = sequences.length;
		for (int i = 0; i < count; ++i) {
			if (sequences[i] == null) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("pjoin" + mm.getMessage("function.invalidParam"));
			}
		}

		if (names == null) {
			names = new String[count];
		} else {
			if (names.length != count) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("pjoin" + mm.getMessage("function.invalidParam"));
			}
		}

		boolean bFirst = false, bUnion = false;
		if (opt != null) {
			if (opt.indexOf('1') != -1)bFirst = true;
			if (opt.indexOf('f') != -1)bUnion = true;
		}

		// "1f"����ͬʱ����
		if (bUnion && bFirst) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(opt + mm.getMessage("engine.optConflict"));
		}

		ListBase1[] srcMems = new ListBase1[count];
		int[] srcLen = new int[count];
		for (int i = 0; i < count; ++i) {
			srcMems[i] = sequences[i].mems;
			srcLen[i] = srcMems[i].size();
		}

		if (bFirst) {
			int totalLen = srcLen[0];
			Table result = new Table(names, totalLen);
			for (int i = 1; i <= totalLen; ++i) {
				Record r = result.newLast();
				r.setNormalFieldValue(0, srcMems[0].get(i));
				for (int c = 1; c < count; ++c) {
					if (i <= srcLen[c]) {
						r.setNormalFieldValue(c, srcMems[c].get(i));
					}
				}
			}
			return result;
		} else {
			int totalLen = srcLen[0];
			if (bUnion) {
				for (int c = 1; c < count; ++c) { // ��󳤶�
					if (totalLen < srcLen[c]) {
						totalLen = srcLen[c];
					}
				}
			} else {
				for (int c = 1; c < count; ++c) { // ��С����
					if (totalLen > srcLen[c]) {
						totalLen = srcLen[c];
					}
				}
			}

			Table result = new Table(names, totalLen);
			for (int i = 1; i <= totalLen; ++i) {
				Record r = result.newLast();
				for (int c = 0; c < count; ++c) {
					if (i <= srcLen[c]) {
						r.setNormalFieldValue(c, srcMems[c].get(i));
					}
				}
			}
			
			return result;
		}
	}

	/**
	 * ��ָ������������
	 * @param sequences ��������
	 * @param exps �����ֶα���ʽ����
	 * @param names ������ֶ�������
	 * @param opt ѡ�1�������ӣ�f��ȫ���ӣ�m�����ݰ������ֶ�������ù鲢��������
	 * @param ctx
	 * @return
	 */
	public static Table join(Sequence[] sequences, Expression[][] exps,
							 String[] names, String opt, Context ctx) {
		int count = sequences.length;
		if (names == null) {
			names = new String[count];
		}

		if (exps == null) {
			exps = new Expression[count][];
		}

		int type = 0; // join
		if (opt != null) {
			if (opt.indexOf('1') != -1) {
				type = 1;
				if (opt.indexOf('f') != -1) { // "1f"����ͬʱ����
					MessageManager mm = EngineMessage.get();
					throw new RQException(opt + mm.getMessage("engine.optConflict"));
				}
			} else if (opt.indexOf('f') != -1) {
				type = 2;
			}
		}

		if (opt == null || opt.indexOf('m') == -1) {
			return CursorUtil.hashJoin(sequences, exps, names, type, ctx);
		} else {
			return CursorUtil.mergeJoin(sequences, exps, names, type, ctx);
		}
	}

	/**
	 * ���ش�����ĳһ�ֶ�ֵ���ɵ�����
	 * @param field int �ֶ���������0��ʼ����
	 * @return Sequence
	 */
	public Sequence fieldValues(int field) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= size; ++i) {
			Record cur = (Record)mems.get(i);
			if (cur == null) {
				resultMems.add(null);
			} else {
				resultMems.add(cur.getFieldValue(field));
			}
		}

		return result;
	}

	/**
	 * ����ĳһ�ֶε�ֵ���ɵ�����
	 * @param fieldName String �ֶ����������Ƕ��ָ���ֶ�ָ����ָ���¼���ֶ���
	 * @return Sequence
	 */
	public Sequence fieldValues(String fieldName) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		Sequence result = new Sequence(size);
		ListBase1 resultMems = result.mems;

		int col = -1; // �ֶ�����һ����¼������
		Record prevRecord = null; // ��һ����¼

		int i = 1;
		while (i <= size) {
			Object obj = mems.get(i++);
			if (obj != null) {
				if (!(obj instanceof Record)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needPmt"));
				}

				prevRecord = (Record)obj;
				col = prevRecord.getFieldIndex(fieldName);
				if (col < 0) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(fieldName + mm.getMessage("ds.fieldNotExist"));
				}

				resultMems.add(prevRecord.getFieldValue(col));
				break;
			} else {
				resultMems.add(null);
			}
		}

		for (; i <= size; ++i) {
			Object obj = mems.get(i);
			if (obj != null) {
				if (!(obj instanceof Record)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needPmt"));
				}

				Record cur = (Record)obj;
				if (!prevRecord.isSameDataStruct(cur)) {
					col = cur.getFieldIndex(fieldName);
					if (col < 0) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(fieldName +
											  mm.getMessage("ds.fieldNotExist"));
					}

					prevRecord = cur;
				}

				resultMems.add(cur.getFieldValue(col));
			} else {
				resultMems.add(null);
			}
		}

		return result;
	}

	// ѡ��ָ���Ķ��й��ɵ�����
	public Table fieldsValues(String[] fieldNames) {
		ListBase1 mems = this.mems;
		int size = length();

		// ���ֶ�������������
		Table retTable = new Table(fieldNames, size);

		int fcount = fieldNames.length;
		Record prevRecord = null; // ��һ����¼
		int[] cols = new int[fcount]; // �ֶ�����һ����¼������

		for (int i = 1; i <= size; ++i) {
			Record newRecord = retTable.newLast();

			Object obj = mems.get(i);
			if (obj == null) {
				continue;
			}

			if (!(obj instanceof Record)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(mm.getMessage("engine.needPmt"));
			}

			Record cur = (Record)obj;
			if (prevRecord != null && prevRecord.isSameDataStruct(cur)) {
				for (int f = 0; f < fcount; ++f) {
					newRecord.setNormalFieldValue(f, cur.getFieldValue(cols[f]));
				}
			} else {
				for (int f = 0; f < fcount; ++f) {
					cols[f] = cur.getFieldIndex(fieldNames[f]);
					if (cols[f] < 0) {
						MessageManager mm = EngineMessage.get();
						throw new RQException(fieldNames[f] +
											  mm.getMessage("ds.fieldNotExist"));
					}

					newRecord.setNormalFieldValue(f, cur.getFieldValue(cols[f]));
				}

				prevRecord = cur;
			}
		}

		return retTable;
	}

	/**
	 * �޸��������м�¼ָ���ֶε��ֶ�ֵ
	 * @param exps ֵ����ʽ����
	 * @param fields �ֶ�������
	 * @param ctx ����������
	 */
	public void modifyFields(Expression []exps, String []fields, Context ctx) {
		ListBase1 mems = this.mems;
		int size = mems.size();
		int fcount = exps.length;
		Record prevRecord = null; // ��һ����¼
		int[] cols = new int[fcount]; // �ֶ�����һ����¼������
		
		// ������ѹջ����������ʽ���õ�ǰ��¼���ֶ�
		ComputeStack stack = ctx.getComputeStack();
		Current current = new Current();
		stack.push(current);
		
		try {
			for (int i = 1; i <= size; ++i) {
				Object obj = mems.get(i);
				if (!(obj instanceof Record)) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needPmt"));
				}
				
				current.setCurrent(i);
				Record r = (Record)obj;
				if (prevRecord != null && prevRecord.isSameDataStruct(r)) {
					for (int f = 0; f < fcount; ++f) {
						r.setNormalFieldValue(cols[f], exps[f].calculate(ctx));
					}
				} else {
					for (int f = 0; f < fcount; ++f) {
						cols[f] = r.getFieldIndex(fields[f]);
						if (cols[f] < 0) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(fields[f] + mm.getMessage("ds.fieldNotExist"));
						}

						r.setNormalFieldValue(cols[f], exps[f].calculate(ctx));
					}

					prevRecord = r;
				}
			}
		} finally {
			stack.pop();
		}
	}
	
	public String toString() {
		ListBase1 mems = this.mems;
		int length = mems.size();
		StringBuffer sb = new StringBuffer(50 * length);
		sb.append(STARTSYMBOL);

		for (int i = 1; i <= length; ++i) {
			if (i > 1) {
				sb.append(SEPARATOR);
			}

			Object obj = mems.get(i);
			if (obj instanceof String) {
				sb.append(Escape.addEscAndQuote((String)obj));
			} else {
				sb.append(Variant.toString(obj));
			}
		}

		sb.append(ENDSYMBOL);
		return sb.toString();
	}

	/**
	 * �Էָ���sep�������г�Ա��Ϊ�ַ���
	 * @param sep String �ָ���
	 * @param opt String c���ö������ӣ�q������Ա����ʱ�������ţ�i���ӵ�����
	 * @return String
	 */
	public String toString(String sep, String opt) {
		boolean addQuotes = false, addSingleQuotes = false, addEnter = false;
		if (opt != null) {
			if (opt.indexOf('c') != -1) sep = ",";
			if (opt.indexOf('q') != -1) addQuotes = true;
			if (opt.indexOf('i') != -1) addSingleQuotes = true;
			if (opt.indexOf('n') != -1) addEnter = true;
		}
		
		ListBase1 mems = this.mems;
		int length = mems.size();
		StringBuffer sb = new StringBuffer(50 * length);
		
		if (addEnter) {
			opt = opt.replace('n', ' ');
			for (int i = 1; i <= length; ++i) {
				Object obj = mems.get(i);
				if (i > 1) {
					sb.append('\n');
				}

				if (obj instanceof String) {
					if (addQuotes) {
						sb.append(Escape.addEscAndQuote((String)obj));
					} else if (addSingleQuotes) {
						sb.append('\'');
						sb.append((String)obj);
						sb.append('\'');
					} else {
						sb.append((String)obj);
					}
				} else if (obj instanceof Sequence) {
					sb.append(((Sequence)obj).toString(sep, opt));
				} else {
					sb.append(Variant.toString(obj));
				}
			}

			return sb.toString();
		} else {
			if (sep == null) {
				sep = ",";
			}

			for (int i = 1; i <= length; ++i) {
				Object obj = mems.get(i);
				if (i > 1) {
					sb.append(sep);
				}

				if (obj instanceof String) {
					if (addQuotes) {
						sb.append(Escape.addEscAndQuote((String)obj));
					} else if (addSingleQuotes) {
						sb.append('\'');
						sb.append((String)obj);
						sb.append('\'');
					} else {
						sb.append((String)obj);
					}
				} else if (obj instanceof Sequence) {
					sb.append(STARTSYMBOL);
					sb.append(((Sequence)obj).toString(sep, opt));
					sb.append(ENDSYMBOL);
				} else {
					sb.append(Variant.toString(obj));
				}
			}

			return sb.toString();
		}
	}

	/**
	 * ������תΪ�ַ���
	 * @return String
	 */
	public String toExportString() {
		ListBase1 mems = this.mems;
		int length = mems.size();
		StringBuffer sb = new StringBuffer(50 * length);
		sb.append(STARTSYMBOL);

		for (int i = 1; i <= length; ++i) {
			if (i > 1) {
				sb.append(SEPARATOR);
			}
			Object obj = mems.get(i);
			sb.append(Variant.toExportString(obj));
		}

		sb.append(ENDSYMBOL);
		return sb.toString();
	}

	/**
	 * ���ִ�src�Էָ���sep������з���
	 * @param src String Դ�ַ���
	 * @param sep String �ָ���
	 * @param opt String p���Զ�ʶ��ɳ�����1���ҵ���һ��seqֹͣ����������Σ�b�����������ź�����ƥ��
	 * @return Sequence
	 */
	public static Sequence toSequence(String src, String sep, String opt) {
		if (src == null || src.length() == 0) {
			return new Sequence(0);
		}
		
		boolean bFirst = false, bMatch = true, bData = false, bTrim = false, bRegex = false, bEnter = false;
		if (opt != null) {
			if (opt.indexOf('p') != -1) bData = true; // �Զ�ʶ��ɳ���
			if (opt.indexOf('1') != -1) bFirst = true; // �ֳ�2��
			if (opt.indexOf('b') != -1) bMatch = false; // ���������ź�����ƥ��
			if (opt.indexOf('t') != -1) bTrim = true;
			if (opt.indexOf('c') != -1) sep = ",";
			if (opt.indexOf('r') != -1) bRegex = true;
			if (opt.indexOf('n') != -1) bEnter = true;
		}

		// ���������ʽ���
		if (bRegex) {
			String []strs = src.split(sep);
			Sequence seq = new Sequence(strs.length);
			if (bTrim) {
				for (String str : strs) {
					str = str.trim();
					if (bData) {
						seq.add(Variant.parse(str));
					} else {
						seq.add(str);
					}
				}
			} else {
				for (String str : strs) {
					if (bData) {
						seq.add(Variant.parse(str));
					} else {
						seq.add(str);
					}
				}
			}
			
			return seq;
		} else if (bEnter) {
			//ɾ��ĩβ�Ŀ���
			int srcLen = src.length();
			int end = srcLen - 1;
			for (; end >= 0; --end) {
				char c = src.charAt(end);
				if (!Character.isWhitespace(c)) {
					break;
				}
			}
			
			if (++end != srcLen) {
				src = src.substring(0, end);
				srcLen = end;
			}
			
			Sequence result = new Sequence();
			opt = opt.replace('n', ' ');
			
			if (bMatch) {
				int match;
				int start = 0;
				int i = 0;
				while (i < srcLen) {
					char c = src.charAt(i);
					switch (c) {
					case '"':
					case '\'':
						match = Sentence.scanQuotation(src, i);
						i = (match == -1) ? srcLen : match + 1;
						continue; // ���������ڵ�����
					case '(':
						match = Sentence.scanParenthesis(src, i);
						i = (match == -1) ? srcLen : match + 1;
						continue; // ���������ڵ�����
					case '[':
						match = Sentence.scanBracket(src, i);
						i = (match == -1) ? srcLen : match + 1;
						continue; // ���������ڵ�����
					case '{':
						match = Sentence.scanBrace(src, i);
						i = (match == -1) ? srcLen : match + 1;
						continue; // ���������ڵ�����
					}

					if (src.charAt(i) == '\r') {
						String sub = src.substring(start, i);
						result.add(toSequence(sub, sep, opt));
						if (src.charAt(++i) == '\n') {
							++i;
						}
						
						start = i;
					} else if (src.charAt(i) == '\n') {
						String sub = src.substring(start, i);
						result.add(toSequence(sub, sep, opt));
						start = ++i;
					} else {
						i++;
					}
				}

				String sub = src.substring(start);
				if (bTrim) {
					sub = sub.trim();
				}
				
				result.add(toSequence(sub, sep, opt));
			} else {
				int start = 0;
				for (; ; ) {
					int index = src.indexOf('\n', start);
					if (index == -1) {
						String sub = src.substring(start);
						if (bTrim) {
							sub = sub.trim();
						}

						result.add(toSequence(sub, sep, opt));
						break;
					} else {
						String sub;
						if (index > start && src.charAt(index - 1) == '\r') {
							sub = src.substring(start, index - 1);
						} else {
							sub = src.substring(start, index);
						}
						
						if (bTrim) {
							sub = sub.trim();
						}

						result.add(toSequence(sub, sep, opt));
						start = index + 1;
					}
				}
			}
			
			return result;
		}

		if (sep == null) {
			sep = ",";
		} else if (sep.length() == 0) {
			char []chars = src.toCharArray();
			int len = chars.length;

			if (bTrim) {
				int start = -1;
				int i = 0;
				for (; i < len; ++i) {
					if (!Character.isWhitespace(chars[i])) {
						start = i;
						break;
					}
				}
				
				if (start == -1) {
					return new Sequence(0);
				}
				
				Sequence result = new Sequence();
				for (++i; i < len; ++i) {
					if (Character.isWhitespace(chars[i])) {
						String sub = src.substring(start, i);
						if (bData) {
							result.add(Variant.parse(sub));
						} else {
							result.add(sub);
						}
						
						start = -1;
						for (++i; i < len; ++i) {
							if (!Character.isWhitespace(chars[i])) {
								start = i;
								break;
							}
						}
					}
				}
				
				if (start != -1) {
					String sub = src.substring(start, len);
					if (bData) {
						result.add(Variant.parse(sub));
					} else {
						result.add(sub);
					}
				}
				
				return result;
			} else {
				Sequence result = new Sequence(len);
				if (bData) {
					for (int i = 0; i < len; ++i) {
						String s = new String(chars, i, 1);
						result.add(Variant.parse(s));
					}
				} else {
					for (int i = 0; i < len; ++i) {
						result.add(new String(chars, i, 1));
					}
				}
				
				return result;
			}
		}

		Sequence result = new Sequence();
		int srcLen = src.length();
		int sepLen = sep.length();

		if (bMatch) {
			int match;
			int start = 0;
			int i = 0;
			while (i < srcLen) {
				char c = src.charAt(i);
				switch (c) {
				case '"':
				case '\'':
					match = Sentence.scanQuotation(src, i);
					i = (match == -1) ? srcLen : match + 1;
					continue; // ���������ڵ�����
				case '(':
					match = Sentence.scanParenthesis(src, i);
					i = (match == -1) ? srcLen : match + 1;
					continue; // ���������ڵ�����
				case '[':
					match = Sentence.scanBracket(src, i);
					i = (match == -1) ? srcLen : match + 1;
					continue; // ���������ڵ�����
				case '{':
					match = Sentence.scanBrace(src, i);
					i = (match == -1) ? srcLen : match + 1;
					continue; // ���������ڵ�����
				}

				if (src.startsWith(sep, i)) {
					if (bData) {
						if (src.charAt(start) == '[' && i > 0 && src.charAt(i - 1) == ']') {
							String sub = src.substring(start + 1, i - 1);
							result.add(toSequence(sub, sep, opt));
						} else {
							String sub = src.substring(start, i);
							if (bTrim) {
								sub = sub.trim();
							}
							
							result.add(Variant.parse(sub));
						}
					} else {
						String sub = src.substring(start, i);
						if (bTrim) {
							sub = sub.trim();
						}
						
						result.add(sub);
					}

					if (bFirst) {
						if (bData) {
							if (src.charAt(i + sepLen) == '[' && src.charAt(srcLen - 1) == ']') {
								String sub = src.substring(i + sepLen + 1, srcLen - 1);
								result.add(toSequence(sub, sep, opt));
							} else {
								String sub = src.substring(i + sepLen);
								if (bTrim) sub = sub.trim();
								
								result.add(Variant.parse(sub));
							}
						} else {
							String sub = src.substring(i + sepLen);
							if (bTrim) sub = sub.trim();

							result.add(sub);
						}

						return result;
					} else {
						i += sepLen;
						start = i;
					}
				} else {
					i++;
				}
			}

			String sub = src.substring(start);
			if (bTrim) {
				sub = sub.trim();
			}
			
			result.add(bData ? Variant.parse(sub) : sub);
		} else {
			int start = 0;
			for (; ; ) {
				int index = src.indexOf(sep, start);
				if (index == -1) {
					String sub = src.substring(start);
					if (bTrim) {
						sub = sub.trim();
					}

					result.add(bData ? Variant.parse(sub) : sub);
					break;
				} else {
					String sub = src.substring(start, index);
					if (bTrim) {
						sub = sub.trim();
					}

					result.add(bData ? Variant.parse(sub) : sub);
					if (bFirst) {
						sub = src.substring(index + sepLen);
						if (bTrim) {
							sub = sub.trim();
						}

						result.add(bData ? Variant.parse(sub) : sub);
						break;
					} else {
						start = index + sepLen;
					}
				}
			}
		}

		return result;
	}

	/**
	 * �������е����ݽṹ��������Ǵ����з��ؿ�
	 * @return DataStruct
	 */
	public DataStruct dataStruct() {
		if (!isPurePmt()) {
			return null;
		}
		return ((Record)ifn()).dataStruct();
	}
	
	/**
	 * ��һ����¼�����ݽṹ����һ�������
	 * @return Table
	 */
	public Table create() {
		Object obj = ifn();
		if (obj instanceof Record) {
			Table table = new Table(((Record)obj).dataStruct());
			return table;
		} else {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}
	}

	/**
	 * �����������Ҽ�¼λ��
	 * @param key Object ����ֵ������ֵ���ɵ�����
	 * @param isSorted boolean �����Ƿ���������
	 * @return int �������� �� -insertpos
	 */
	public int pfindByKey(Object key, boolean isSorted) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		if (len == 0) {
			return -1;
		}
		
		Object startVal = mems.get(1);
		DataStruct ds = null;
		if (startVal instanceof Record) {
			ds = ((Record)startVal).dataStruct();
		}
		
		// �ж��Ƿ��Ǵ����¼���ά��
		if (ds != null && ds.getTimeKeyCount() > 0) {
			int []baseKeyIndex = ds.getBaseKeyIndex();
			int timeKeyIndex = ds.getTimeKeyIndex();
			int baseKeyCount = baseKeyIndex.length;
			Object []baseKeyValues = null;
			Object timeKeyValue = null;
			
			if (key instanceof Sequence) {
				Sequence seq = (Sequence)key;
				if (seq.length() == baseKeyCount) {
					baseKeyValues = seq.toArray();
				} else if (seq.length() == baseKeyCount + 1) {
					baseKeyValues = new Object[baseKeyCount];
					timeKeyValue = seq.getMem(baseKeyCount + 1);
					for (int i = 1; i <= baseKeyCount; ++i) {
						baseKeyValues[i - 1] = seq.getMem(i);
					}
				} else {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.keyValCountNotMatch"));
				}
			} else {
				if (baseKeyCount != 1) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.keyValCountNotMatch"));
				}
				
				baseKeyValues = new Object[] {key};
			}
			
			if (isSorted) {
				// �����ö��ַ�����
				int index = -1;
				int low = 1, high = len;
				while (low <= high) {
					int mid = (low + high) >> 1;
					Record r = (Record)mems.get(mid);
					int value = r.compare(baseKeyIndex, baseKeyValues);
					if (value < 0) {
						low = mid + 1;
					} else if (value > 0) {
						high = mid - 1;
					} else { // key found
						index = mid;
						break;
					}
				}
				
				if (index == -1) {
					return -low;
				} else if (timeKeyValue == null) {
					// û��ָ��ʱ������ֶμ�ʱȡ���µ�
					for (++index; index <= len; ++index) {
						Record r = (Record)mems.get(index);
						if (r.compare(baseKeyIndex, baseKeyValues) != 0) {
							break;
						}
					}
					
					return index - 1;
				} else {
					// ָ��ʱ������ֶμ�ʱȡǰ�������
					Record r = (Record)mems.get(index);
					int cmp = Variant.compare(r.getNormalFieldValue(timeKeyIndex), timeKeyValue, true);
					if (cmp == 0) {
						return index;
					} else if (cmp > 0) {
						for (--index; index > 0; --index) {
							r = (Record)mems.get(index);
							if (r.compare(baseKeyIndex, baseKeyValues) != 0) {
								return -index - 1;
							} else if (Variant.compare(r.getNormalFieldValue(timeKeyIndex), timeKeyValue, true) <= 0) {
								return index;
							}
						}
						
						return -1;
					} else {
						for (++index; index <= len; ++index) {
							r = (Record)mems.get(index);
							if (r.compare(baseKeyIndex, baseKeyValues) != 0) {
								break;
							}
							
							cmp = Variant.compare(r.getNormalFieldValue(timeKeyIndex), timeKeyValue, true);
							if (cmp == 0) {
								return index;
							} else if (cmp > 0) {
								break;
							}
						}
						
						return index - 1;
					}
				}
			} else {
				int prevIndex = 0; // ��һ�����������ļ�¼������
				Object prevTimeValue = null; // ��һ�����������ļ�¼��ʱ�����ֵ��ȡ�����ʱ���
				
				for (int i = 1; i <= len; ++i) {
					Object obj = mems.get(i);
					Record r = (Record)obj;
					if (r.compare(baseKeyIndex, baseKeyValues) == 0) {
						Object curTimeValue = r.getNormalFieldValue(timeKeyIndex);
						int cmp = Variant.compare(curTimeValue, timeKeyValue);
						if (cmp == 0) {
							return i;
						} else if (cmp < 0) {
							if (prevIndex == 0 || Variant.compare(curTimeValue, prevTimeValue) > 0) {
								prevIndex = i;
								prevTimeValue = curTimeValue;
							}
						}
					}
				}
				
				return prevIndex;
			}
		} else {			
			if (key instanceof Sequence) {
				// key�������ӱ��ļ�¼������������B
				Sequence seq = (Sequence)key;
				int klen = seq.length();
				if (klen == 0) {
					return 0;
				}
				
				if (startVal instanceof Record) {
					startVal = ((Record)startVal).getPKValue();
				}
				
				if (startVal instanceof Sequence) {
					int klen2 = ((Sequence)startVal).length();
					if (klen > klen2) {
						key = seq.get(1, klen2 + 1);
					}
				} else {
					key = seq.getMem(1);
				}
			}

			if (isSorted) {
				int low = 1, high = len;
				while (low <= high) {
					int mid = (low + high) >> 1;
					Object obj = mems.get(mid);
					Object keyVal;
					if (obj instanceof Record) {
						keyVal = ((Record)obj).getPKValue();
					} else {
						keyVal = obj;
					}

					int value = Variant.compare(keyVal, key);
					if (value < 0) {
						low = mid + 1;
					} else if (value > 0) {
						high = mid - 1;
					} else { // key found
						return mid;
					}
				}

				return -low;
			} else {
				for (int i = 1; i <= len; ++i) {
					Object obj = mems.get(i);
					if (obj instanceof Record) {
						if (Variant.isEquals(((Record)obj).getPKValue(), key)) {
							return i;
						}
					} else {
						if (Variant.isEquals(obj, key)) {
							return i;
						}
					}
				}

				return 0;
			}
		}
	}
	
	/**
	 * ���а�ָ���ֶ������ö��ַ������ֶε���ָ��ֵ�ļ�¼
	 * @param fvals ֵ����
	 * @param findex �ֶ�����
	 * @return λ�ã��Ҳ������ظ��Ĳ���λ��
	 */
	public int pfindByFields(Object []fvals, int []findex) {
		ListBase1 mems = this.mems;
		int len = mems.size();
		int fcount = findex.length;
		Object []vals = new Object[fcount];

		int low = 1, high = len;
		while (low <= high) {
			int mid = (low + high) >> 1;
			Record r = (Record)mems.get(mid);
			for (int f = 0; f < fcount; ++f) {
				vals[f] = r.getNormalFieldValue(findex[f]);
			}

			int value = Variant.compareArrays(vals, fvals);
			if (value < 0) {
				low = mid + 1;
			} else if (value > 0) {
				high = mid - 1;
			} else { // key found
				return mid;
			}
		}

		return -low;
	}

	public boolean equals(Object obj) {
		if (!(obj instanceof Sequence)) {
			return false;
		}
		
		ListBase1 mems = this.mems;
		ListBase1 mems2 = ((Sequence)obj).mems;
		int len = mems.size();
		if (len != mems2.size()) return false;
		
		for (int i = 1; i <= len; ++i) {
			if (!Variant.isEquals(mems.get(i), mems2.get(i))) {
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * �Ƚ������еĴ�С
	 * @param seq Sequence
	 * @return 1����ǰ���д�0������������ȣ�-1����ǰ����С
	 */
	public int compareTo(Sequence other) {
		return cmp(other);
	}

	/**
	 * ���������ֶε�ֵ����key�ļ�¼���������򷵻ؿ�
	 * @param key Object �����ҵ������ֶε�ֵ
	 * @param isSorted boolean �����Ƿ������ֶ�����
	 * @return Object
	 */
	public Object findByKey(Object key, boolean isSorted) {
		if (isSorted) {
			int index = pfindByKey(key, isSorted);
			return index > 0 ? mems.get(index) : null;
		} else {
			IndexTable indexTable = getIndexTable();
			if (indexTable == null) {
				int index = pfindByKey(key, isSorted);
				return index > 0 ? mems.get(index) : null;
			} else {
				if (key instanceof Sequence) {
					// key�������ӱ��ļ�¼������������B
					Sequence seq = (Sequence)key;
					int klen = seq.length();
					if (klen == 0 || length() == 0) {
						return null;
					}
					
					int keyCount = 1;					
					Object startVal = mems.get(1);
					if (startVal instanceof Record) {
						int []pkIndex = ((Record)startVal).getPKIndex();
						if (pkIndex == null) {
							MessageManager mm = EngineMessage.get();
							throw new RQException(mm.getMessage("ds.lessKey"));
						}
						
						keyCount = pkIndex.length;
					} else if (startVal instanceof Sequence) {
						keyCount = ((Sequence)startVal).length();
					}
					
					
					if (keyCount > 1) {
						if (klen > keyCount) {
							Object []vals = new Object[keyCount];
							for (int i = 1; i <= keyCount; ++i) {
								vals[i - 1] = seq.getMem(i);
							}

							return indexTable.find(vals);
						} else {
							return indexTable.find(seq.toArray());
						}
					} else {
						return indexTable.find(seq.getMem(1));
					}
				} else {
					return indexTable.find(key);
				}
			}
		}
	}

	/**
	 * �����ֶ�ֵ����ĳ����¼
	 * @param keyIndex int[] ��������
	 * @param values Object[] ����ֵ
	 * @param isSorted boolean �����Ƿ���������
	 * @return Record
	 */
	public Record select(int[] keyIndex, Object[] values, boolean isSorted) {
		ListBase1 mems = this.mems;
		int sLength = mems.size();

		if (isSorted) {
			int low = 1, high = sLength;
			while (low <= high) {
				int mid = (low + high) >> 1;
				Record r = (Record)mems.get(mid);

				int value = r.compare(keyIndex, values);
				if (value < 0) {
					low = mid + 1;
				} else if (value > 0) {
					high = mid - 1;
				} else { // key found
					return r;
				}
			}
		} else {
			int fcount = keyIndex.length;
			Next:
			for (int i = 1; i <= sLength; ++i) {
				Record r = (Record)mems.get(i);
				for (int f = 0; f < fcount; ++f) {
					if (!Variant.isEquals(r.getFieldValue(keyIndex[f]), values[f])) {
						continue Next;
					}
				}
				
				return r;
			}
		}

		return null;
	}

	/**
	 * ���ؼ�¼���������������й��ɵ�����
	 * @return Object
	 */
	public Sequence getPKeyValues() {
		ListBase1 mems = this.mems;
		int len = mems.size();
		Sequence result = new Sequence(len);
		ListBase1 resultMems = result.mems;

		for (int i = 1; i <= len; ++i) {
			Object obj = mems.get(i);
			if (obj instanceof Record) {
				resultMems.add(((Record)obj).getPKValue());
			} else {
				resultMems.add(obj);
			}
		}

		return result;
	}

	/**
	 * �Ա���¼��switch�任
	 * @param fkName ����ֶ�
	 * @param code Sequence �����������Ψһ
	 * @param exp Expression ���������
	 * @param opt String
	 * @param ctx Context
	 */
	public void switchFk(String fkName, Sequence code, Expression exp, String opt, Context ctx) {
		ListBase1 mems = this.mems;
		if (mems.size == 0) {
			return;
		}
		
		if (code != null) {
			CursorUtil.hashSwitch(this, fkName, code, exp, opt, ctx);
			return;
		}

		DataStruct ds = dataStruct();
		if (ds == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}

		int fkIndex = ds.getFieldIndex(fkName);
		if (fkIndex == -1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(fkName + mm.getMessage("ds.fieldNotExist"));
		}

		for (int i = 1, len = mems.size(); i <= len; ++i) {
			Record r = (Record)mems.get(i);
			Object fval = r.getNormalFieldValue(fkIndex);
			if (fval instanceof Record) {
				r.setNormalFieldValue(fkIndex, ((Record)fval).getPKValue());
			}
		}
	}

	public void sortFields(String []cols) {
		DataStruct ds = dataStruct();
		if (ds == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}

		int colCount = cols.length;
		int []colIndex = new int[colCount];
		for (int i = 0; i < colCount; ++i) {
			colIndex[i] = ds.getFieldIndex(cols[i]);
			if (colIndex[i] == -1) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(cols[i] + mm.getMessage("ds.fieldNotExist"));
			}
		}

		Comparator<Object> comparator = new RecordFieldComparator(colIndex);
		mems.sort(comparator);
	}

	/**
	 * ȡ�����������������������򷵻ؿ�
	 * @return �����������
	 */
	public IndexTable getIndexTable() {
		return null;
	}

	/**
	 * ȡ�����������������������򷵻ؿ�
	 * @param exp �����ֶα���ʽ��һ��Ϊ�����ֶ�
	 * @param ctx ����������
	 * @return �����������
	 */
	public IndexTable getIndexTable(Expression exp, Context ctx) {
		return null;
	}
	
	/**
	 * ȡ�����������������������򷵻ؿ�
	 * @param exps �����ֶα���ʽ���飬һ��Ϊ���ֶ�����
	 * @param ctx ����������
	 * @return �����������
	 */
	public IndexTable getIndexTable(Expression []exps, Context ctx) {
		return null;
	}
}