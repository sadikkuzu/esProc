package com.scudata.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.TreeMap;

import com.scudata.common.IntArrayList;
import com.scudata.common.Logger;
import com.scudata.common.MessageManager;
import com.scudata.common.RQException;
import com.scudata.dm.BFileReader;
import com.scudata.dm.BFileWriter;
import com.scudata.dm.ComputeStack;
import com.scudata.dm.Context;
import com.scudata.dm.DataStruct;
import com.scudata.dm.Env;
import com.scudata.dm.FileObject;
import com.scudata.dm.HashArraySet;
import com.scudata.dm.IndexTable;
import com.scudata.dm.ListBase1;
import com.scudata.dm.Record;
import com.scudata.dm.Sequence;
import com.scudata.dm.Table;
import com.scudata.dm.comparator.*;
import com.scudata.dm.cursor.*;
import com.scudata.dm.op.DiffJoin;
import com.scudata.dm.op.FilterJoin;
import com.scudata.dm.op.IGroupsResult;
import com.scudata.dm.op.Join;
import com.scudata.dm.op.Operation;
import com.scudata.dw.ColumnTableMetaData;
import com.scudata.dw.IDWCursor;
import com.scudata.dw.TableMetaData;
import com.scudata.expression.CurrentSeq;
import com.scudata.expression.Expression;
import com.scudata.expression.Node;
import com.scudata.parallel.ClusterCursor;
import com.scudata.resources.EngineMessage;
import com.scudata.thread.GroupsJob;
import com.scudata.thread.GroupxJob;
import com.scudata.thread.MultithreadUtil;
import com.scudata.thread.ThreadPool;

public final class CursorUtil {
	/**
	 * ȡ�α�����ݽṹ
	 * @param cs
	 * @return
	 */
	public static DataStruct getDataStruct(ICursor cs) {
		DataStruct ds = cs.getDataStruct();
		if (ds == null) {
			Sequence seq = cs.peek(ICursor.FETCHCOUNT_M);
			ds = cs.getDataStruct();
			if (ds == null && seq != null) {
				ds = seq.dataStruct();
			}
		}
		
		return ds;
	}

	/**
	 * �����н��в��з���
	 * @param src ����
	 * @param exps �����ֶα���ʽ����
	 * @param names �����ֶ�������
	 * @param calcExps �����ֶα���ʽ����
	 * @param calcNames �����ֶ�������
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return
	 */
	public static Table groups_m(Sequence src, Expression[] exps, String[] names,
			Expression[] calcExps, String[] calcNames, String opt, Context ctx) {
		int len = src.length();
		int parallelNum = Env.getParallelNum();
		if (len <= MultithreadUtil.SINGLE_PROSS_COUNT || parallelNum < 2) {
			return src.groups(exps, names, calcExps, calcNames, opt, ctx);
		}
		
		int threadCount = (len - 1) / MultithreadUtil.SINGLE_PROSS_COUNT + 1;
		if (threadCount > parallelNum) {
			threadCount = parallelNum;
		}
		
		int singleCount = len / threadCount;
		int keyCount = exps == null ? 0 : exps.length;
		int valCount = calcExps == null ? 0 : calcExps.length;
		String option = opt == null ? "u" : opt + "u";
		
		if (valCount > 0) {			
			// ���ɽ������ͳ��������
			if (calcNames == null) calcNames = new String[valCount];
			for (int i = 0; i < valCount; ++i) {
				if (calcNames[i] == null || calcNames[i].length() == 0) {
					calcNames[i] = calcExps[i].getFieldName();
				}
			}
		}
		
		// ���ɷ��������ύ���̳߳�
		ThreadPool pool = ThreadPool.instance();
		GroupsJob []jobs = new GroupsJob[threadCount];
		
		int start = 1;
		int end; // ������
		for (int i = 0; i < threadCount; ++i) {
			if (i + 1 == threadCount) {
				end = len + 1;
			} else {
				end = start + singleCount;
			}

			Context tmpCtx = ctx.newComputeContext();
			Expression []tmpExps = null;
			Expression []tmpCalcExps = null;
			if (keyCount > 0) {
				tmpExps = new Expression[keyCount];
				for (int k = 0; k < keyCount; ++k) {
					tmpExps [k] = exps[k].newExpression(tmpCtx);
				}
			}
			
			if (valCount > 0) {
				tmpCalcExps = new Expression[valCount];
				for (int v = 0; v < valCount; ++v) {
					tmpCalcExps [v] = calcExps[v].newExpression(tmpCtx);
				}
			}
			
			Sequence seq = src.get(start, end);
			jobs[i] = new GroupsJob(seq, tmpExps, names, tmpCalcExps, calcNames, option, tmpCtx);
			pool.submit(jobs[i]);
			start = end;
		}
		
		// �ȴ���������ִ����ϣ����ѽ�����ӵ�һ�����
		Table result = null;
		for (int i = 0; i < threadCount; ++i) {
			jobs[i].join();
			if (result == null) {
				result = jobs[i].getResult();
			} else {
				result.addAll(jobs[i].getResult());
			}
		}
		
		if (result == null || result.length() == 0) {
			return result;
		}
		
		// ���ɶ��η���������ʽ
		Expression []keyExps = null;
		if (keyCount > 0) {
			keyExps = new Expression[keyCount];
			for (int i = 0, q = 1; i < keyCount; ++i, ++q) {
				keyExps[i] = new Expression(ctx, "#" + q);
			}
		}

		// ���ɶ��η�����ܱ���ʽ
		Expression []valExps = null;
		if (valCount > 0) {
			valExps = new Expression[valCount];
			for (int i = 0, q = keyCount + 1; i < valCount; ++i, ++q) {
				Node gather = calcExps[i].getHome();
				gather.prepare(ctx);
				valExps[i] = gather.getRegatherExpression(q);
			}
		}

		// ���ж��η���
		return result.groups(keyExps, names, valExps, calcNames, opt, ctx);
	}
		
	/**
	 * �趨�������������������ﵽ���ֵ��ֹͣ����
	 * @param cursor �α�
	 * @param exps �����ֶα���ʽ����
	 * @param names �����ֶ�������
	 * @param calcExps �����ֶα���ʽ����
	 * @param calcNames �����ֶ�������
	 * @param maxGroupCount
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return ������
	 */
	public static Table fuzzyGroups(ICursor cursor, Expression[] exps, String[] names, 
			Expression[] calcExps, String[] calcNames, String opt, Context ctx, int maxGroupCount) {
		int count = exps.length;
		if (names == null) names = new String[count];
		for (int i = 0; i < count; ++i) {
			if (names[i] == null || names[i].length() == 0) {
				names[i] = exps[i].getIdentifierName();
			}
		}

		if (calcExps != null) {
			count = calcExps.length;
			if (calcNames == null) calcNames = new String[count];
			for (int i = 0; i < count; ++i) {
				if (calcNames[i] == null || calcNames[i].length() == 0) {
					calcNames[i] = calcExps[i].getIdentifierName();
				}
			}
		}

		int keyCount = exps.length;
		int valCount = calcExps == null ? 0 : calcExps.length;

		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil(maxGroupCount);
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];
		Object []keys = new Object[keyCount];
		String[] colNames = new String[keyCount + valCount];
		System.arraycopy(names, 0, colNames, 0, keyCount);

		if (calcNames != null) {
			System.arraycopy(calcNames, 0, colNames, keyCount, valCount);
		}

		Table table = new Table(colNames, hashUtil.getCapacity());
		table.setPrimary(names);
		Node []gathers = Sequence.prepareGatherMethods(calcExps, ctx);

		ComputeStack stack = ctx.getComputeStack();
		while (true) {
			Sequence src = cursor.fetch(ICursor.FETCHCOUNT);
			if (src == null || src.length() == 0) break;

			Sequence.Current current = src.new Current();
			stack.push(current);

			try {
				for (int i = 1, len = src.length(); i <= len; ++i) {
					current.setCurrent(i);
					for (int k = 0; k < keyCount; ++k) {
						keys[k] = exps[k].calculate(ctx);
					}

					Record r;
					int hash = hashUtil.hashCode(keys);
					if (groups[hash] == null) {
						groups[hash] = new ListBase1(INIT_GROUPSIZE);
						r = table.newLast(keys);
						groups[hash].add(r);
						for (int v = 0, f = keyCount; v < valCount; ++v, ++f) {
							Object val = gathers[v].gather(ctx);
							r.setNormalFieldValue(f, val);
						}
					} else {
						int index = HashUtil.bsearch_r(groups[hash], keys);
						if (index < 1) {
							r = table.newLast(keys);
							groups[hash].add(-index, r);
							for (int v = 0, f = keyCount; v < valCount; ++v, ++f) {
								Object val = gathers[v].gather(ctx);
								r.setNormalFieldValue(f, val);
							}
						} else {
							r = (Record)groups[hash].get(index);
							for (int v = 0, f = keyCount; v < valCount; ++v, ++f) {
								Object val = gathers[v].gather(r.getNormalFieldValue(f), ctx);
								r.setNormalFieldValue(f, val);
							}
						}
					}
				}
			} finally {
				stack.pop();
			}

			if (table.length() >= maxGroupCount) {
				break;
			}
		}

		if (opt == null || opt.indexOf('u') == -1) {
			int []fields = new int[keyCount];
			for (int i = 0; i < keyCount; ++i) {
				fields[i] = i;
			}

			table.sortFields(fields);
		}

		table.finishGather(gathers);
		return table;
	}

	/**
	 * �����н��й�ϣ����
	 * @param src ����
	 * @param exps �������ʽ����
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return ������
	 */
	public static Sequence hashGroup(Sequence src, Expression[] exps, String opt, Context ctx) {
		if (src == null || src.length() == 0) return new Sequence(0);

		boolean isAll = true, isSort = true, isPos = false, isConj = false;
		if (opt != null) {
			if (opt.indexOf('1') != -1) isAll = false;
			if (opt.indexOf('u') != -1) isSort = false;
			if (opt.indexOf('p') != -1) isPos = true;
			if (opt.indexOf('s') != -1) isConj = true;
		}

		int keyCount = exps.length;
		int count = isAll ? keyCount + 1 : keyCount;
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil();
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];
		Sequence result = new Sequence(hashUtil.getCapacity());
		ListBase1 keyList = null;
		if (isSort) {
			keyList = new ListBase1(hashUtil.getCapacity());
		}

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = src.new Current();
		stack.push(current);

		try {
			for (int i = 1, len = src.length(); i <= len; ++i) {
				current.setCurrent(i);
				Object []keys = new Object[count];
				for (int k = 0; k < keyCount; ++k) {
					keys[k] = exps[k].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					if (isAll) {
						Sequence group = new Sequence(INIT_GROUPSIZE);
						group.add(isPos ? new Integer(i):current.getCurrent());
						keys[keyCount] = group;
						result.add(group);
					} else {
						result.add(isPos ? new Integer(i):current.getCurrent());
					}

					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
					if (isSort) {
						keyList.add(keys);
					}
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						if (isAll) {
							Sequence group = new Sequence(INIT_GROUPSIZE);
							group.add(isPos ? new Integer(i):current.getCurrent());
							keys[keyCount] = group;
							result.add(group);
						} else {
							result.add(isPos ? new Integer(i):current.getCurrent());
						}

						groups[hash].add(-index, keys);
						if (isSort) {
							keyList.add(keys);
						}
					} else {
						if (isAll) {
							Object []tmps = (Object[])groups[hash].get(index);
							((Sequence)tmps[keyCount]).add(isPos ? new Integer(i):current.getCurrent());
						}
					}
				}
			}
		} finally {
			stack.pop();
		}

		if (isSort) {
			int len = result.length();
			PSortItem []infos = new PSortItem[len + 1];
			for (int i = 1; i <= len; ++i) {
				infos[i] = new PSortItem(i, keyList.get(i));
			}

			Comparator<Object> comparator = new ArrayComparator(keyCount);
			comparator = new PSortComparator(comparator);

			MultithreadUtil.sort(infos, 1, infos.length, comparator);

			Sequence retSeries = new Sequence(len);
			for (int i = 1; i <= len; ++i) {
				retSeries.add(result.getMem(infos[i].index));
			}

			if (isAll && isConj) {
				return retSeries.conj(null);
			} else {
				return retSeries;
			}
		} else {
			if (isAll && isConj) {
				return result.conj(null);
			} else {
				return result;
			}
		}
	}

	/**
	 * �����н��й�ϣ����
	 * @param src ����
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return ������
	 */
	public static Sequence hashGroup(Sequence src, String opt) {
		if (src == null || src.length() == 0) return new Sequence(0);
		boolean isAll = true, isSort = true, isPos = false, isConj = false;
		if (opt != null) {
			if (opt.indexOf('1') != -1) isAll = false;
			if (opt.indexOf('u') != -1) isSort = false;
			if (opt.indexOf('p') != -1) isPos = true;
			if (opt.indexOf('s') != -1) isConj = true;
		}
		
		if (!isAll) {
			return hashId(src, opt);
		} else if (isPos) {
			Context ctx = new Context();
			Expression exp = new Expression("~");
			return hashGroup(src, new Expression[] {exp}, opt, ctx);
		}

		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil();
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];
		Sequence result = new Sequence(hashUtil.getCapacity());

		for (int i = 1, len = src.length(); i <= len; ++i) {
			Object mem = src.getMem(i);
			int hash = hashUtil.hashCode(mem);
			if (groups[hash] == null) {
				Sequence group = new Sequence(INIT_GROUPSIZE);
				group.add(mem);
				result.add(group);

				groups[hash] = new ListBase1(INIT_GROUPSIZE);
				groups[hash].add(group);
			} else {
				int index = HashUtil.bsearch_g(groups[hash], mem);
				if (index < 1) {
					Sequence group = new Sequence(INIT_GROUPSIZE);
					group.add(mem);
					result.add(group);

					groups[hash].add(-index, group);
				} else {
					Sequence group = (Sequence)groups[hash].get(index);
					group.add(mem);
				}
			}
		}

		if (isSort) {
			Comparator<Object> comparator = new Comparator<Object>() {
				public int compare(Object o1, Object o2) {
					return Variant.compare(((Sequence)o1).getMem(1), ((Sequence)o2).getMem(1), true);
				}
			};

			result.getMems().sort(comparator);
		}

		if (isConj) {
			return result.conj(null);
		} else {
			return result;
		}
	}

	/**
	 * �����н��й�ϣȥ��
	 * @param src ����
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return ������
	 */
	public static Sequence hashId(Sequence src, String opt) {
		int len = src.length();
		if (len == 0) {
			return new Sequence();
		}

		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil(len / 2);
		Sequence out = new Sequence(len);
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		for (int i = 1; i <= len; ++i) {
			Object item = src.getMem(i);
			int hash = hashUtil.hashCode(item);
			if (groups[hash] == null) {
				groups[hash] = new ListBase1(INIT_GROUPSIZE);
				groups[hash].add(item);
				out.add(item);
			} else {
				int index = groups[hash].binarySearch(item);
				if (index < 1) {
					groups[hash].add(-index, item);
					out.add(item);
				}
			}
		}

		if (opt == null || opt.indexOf('u') == -1) {
			Comparator<Object> comparator = new BaseComparator();
			out.getMems().sort(comparator);
		}

		return out;
	}
	
	/**
	 * �������б��������鲢����
	 * @param groups �б�����
	 * @param keyCount �����ֶ���
	 * @param type �������ͣ�0��join��1��left join��2��full join
	 * @param out
	 */
	public static void join_m(ListBase1 []groups, int fcount, int type, Table out) {
		int srcCount = groups.length;
		int []ranks = new int[srcCount]; // ��ǰԪ�ص�������0��1��-1
		int []curIndex = new int[srcCount];
		for (int i = 0; i < srcCount; ++i) {
			curIndex[i] = 1;
		}

		Next:
		while (true) {
			boolean has = false; // �Ƿ��еı���û������
			boolean equals = true; // �Ƿ����ܹ����ϵļ�¼
			for (int i = 0; i < srcCount; ++i) {
				ListBase1 group = groups[i];
				if (group != null && group.size() >= curIndex[i]) {
					has = true;
					ranks[i] = 0;
					Object []curValues = (Object[])group.get(curIndex[i]);
					
					// ��ǰ��������ֶε�ֵ��С�ı����бȽϣ�
					for (int j = 0; j < i; ++j) {
						if (ranks[j] == 0) {
							Object [] prevValues = (Object[])groups[j].get(curIndex[j]);
							int cmp = Variant.compareArrays(curValues, prevValues, fcount);
							
							// ������淢�ָ�С��ֵ���޸�����
							if (cmp < 0) {
								equals = false;
								ranks[j] = 1;
								for (++j; j < i; ++j) {
									if (ranks[j] == 0) {
										ranks[j] = 1;
									}
								}
							} else if (cmp > 0) {
								equals = false;
								ranks[i] = 1;
							}

							break;
						}
					}
				} else {
					// ��ǰ�������Ѿ�������
					if (type == 0 || (type == 1 && i == 0)) { // join
						break Next;
					}

					equals = false;
					ranks[i] = -1;
				}
			}

			if (!has) {
				break;
			} else if ((!equals && type == 0) || (ranks[0] != 0 && type == 1)) {
				// ������ڹ������ϵı��������������ӻ����������Ӷ���һ������ֵ�ֲ�����С������������Ϊ0�ļ�¼
				for (int i = 0; i < srcCount; ++i) {
					if (ranks[i] == 0) {
						ListBase1 group = groups[i];
						int len = group.size();
						int cur = curIndex[i];
						Object []curValues = (Object[])group.get(cur);

						for (++cur; cur <= len; ++cur) {
							if (Variant.compareArrays(curValues, (Object[])group.get(cur), fcount) != 0) {
								break;
							}
						}

						curIndex[i] = cur;
					}
				}
			} else {
				// ���ɹ�����¼
				int start = -1;
				for (int i = 0; i < srcCount; ++i) {
					if (ranks[i] == 0) {
						ListBase1 group = groups[i];
						int len = group.size();
						int cur = curIndex[i];
						Object []curValues = (Object[])group.get(cur);

						if (start == -1) {
							Record r = out.newLast();
							r.setNormalFieldValue(i, curValues[fcount]);
							start = out.length();

							for (++cur; cur <= len; ++cur) {
								Object []tmp = (Object[])group.get(cur);
								if (Variant.compareArrays(curValues, tmp, fcount) == 0) {
									r = out.newLast();
									r.setNormalFieldValue(i, tmp[fcount]);
								} else {
									break;
								}
							}

							curIndex[i] = cur;
						} else {
							int end = out.length();
							for (int p = start; p <= end; ++p) {
								Record pr = (Record)out.getMem(p);
								pr.setNormalFieldValue(i, curValues[fcount]);
							}

							for (++cur; cur <= len; ++cur) {
								Object []tmp = (Object[])group.get(cur);
								if (Variant.compareArrays(curValues, tmp, fcount) == 0) {
									for (int p = start; p <= end; ++p) {
										Record pr = (Record)out.getMem(p);
										Record r = out.newLast(pr.getFieldValues());
										r.setNormalFieldValue(i, tmp[fcount]);
									}
								} else {
									break;
								}
							}

							curIndex[i] = cur;
						}
					}
				}
			}
		}
	}

	/**
	 * Դ���ݰ������ֶ��������鲢����
	 * @param srcs ��������
	 * @param exps �����ֶα���ʽ����
	 * @param names ������ֶ�������
	 * @param type �������ͣ�0��join��1��left join��2��full join
	 * @param ctx Context ����������
	 * @return Table �������
	 */
	public static Table mergeJoin(Sequence[] srcs, Expression[][] exps,
								  String[] names, int type, Context ctx) {
		int srcCount = srcs.length;
		ListBase1 []groups = new ListBase1[srcCount];
		int keyCount = exps[0] == null ? 1 : exps[0].length;
		int count = keyCount + 1;

		ComputeStack stack = ctx.getComputeStack();
		for (int s = 0; s < srcCount; ++s) {
			Sequence src = srcs[s];
			int len = src.length();
			ListBase1 group = new ListBase1(len);
			groups[s] = group;
			Expression []srcExps = exps[s];

			Sequence.Current current = src.new Current();
			stack.push(current);

			try {
				// ��������ֶ�ֵ��Դ��¼������������λ��
				for (int i = 1; i <= len; ++i) {
					Object []keys = new Object[count];
					keys[keyCount] = src.getMem(i);
					current.setCurrent(i);
					if (srcExps == null) {
						keys[0] = keys[keyCount];
					} else {
						for (int k = 0; k < keyCount; ++k) {
							keys[k] = srcExps[k].calculate(ctx);
						}
					}

					groups[s].add(keys);
				}
			} finally {
				stack.pop();
			}
		}

		Table out = new Table(names);
		join_m(groups, keyCount, type, out);
		return out;
	}

	/**
	 * �����ϣֵ�����ù�ϣֵ����join
	 * @param srcs ��������
	 * @param exps �����ֶα���ʽ����
	 * @param names ������ֶ�������
	 * @param type �������ͣ�0��join��1��left join��2��full join
	 * @param ctx Context ����������
	 * @return Table �������
	 */
	public static Table hashJoin(Sequence[] srcs, Expression[][] exps,
								 String[] names, int type, Context ctx) {
		int srcCount = srcs.length;
		int keyCount = exps[0] == null ? 1 : exps[0].length;
		int count = keyCount + 1;

		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil();
		ListBase1 [][]hashGroups = new ListBase1[hashUtil.getCapacity()][];
		ComputeStack stack = ctx.getComputeStack();

		// ��ÿ�����а��չ����ֶν��й�ϣ����
		for (int s = 0; s < srcCount; ++s) {
			Sequence src = srcs[s];
			Expression []srcExps = exps[s];

			Sequence.Current current = src.new Current();
			stack.push(current);

			try {
				for (int i = 1, len = src.length(); i <= len; ++i) {
					Object []keys = new Object[count];
					keys[keyCount] = src.getMem(i);
					current.setCurrent(i);
					if (srcExps == null) {
						keys[0] = keys[keyCount];
					} else {
						for (int k = 0; k < keyCount; ++k) {
							keys[k] = srcExps[k].calculate(ctx);
						}
					}

					int hash = hashUtil.hashCode(keys, keyCount);
					ListBase1 []groups = hashGroups[hash];
					if (groups == null) {
						groups = new ListBase1[srcCount];
						hashGroups[hash] = groups;
					}

					if (groups[s] == null) {
						groups[s] = new ListBase1(INIT_GROUPSIZE);
						groups[s].add(keys);
					} else {
						// ��ϣֵ��ͬ��Ԫ�ذ��չ����ֶ����򱣴�
						int index = HashUtil.bsearch_a(groups[s], keys, keyCount);
						if (index < 1) {
							groups[s].add(-index, keys);
						} else {
							groups[s].add(index + 1, keys);
						}
					}
				}
			} finally {
				stack.pop();
			}
		}

		Table out = new Table(names);
		for (int i = 0, len = hashGroups.length; i < len; ++i) {
			// ��ÿ����ϣ��������鲢����
			if (hashGroups[i] != null) {
				join_m(hashGroups[i], keyCount, type, out);
				hashGroups[i] = null;
			}
		}

		return out;
	}
	
	/**
	 * �����ֶ�������ͬ������
	 * @param srcs ��������
	 * @param exps �����ֶα���ʽ����
	 * @param names ������ֶ�������
	 * @param type �������ͣ�0��join��1��left join��2��full join
	 * @param ctx Context ����������
	 * @return Table �������
	 */
	public static Table mixJoin(Sequence[] srcs, Expression[][] exps,
			 String[] names, int type, Context ctx) {
		int tcount = srcs.length;
		int expCount = exps[0].length;
		Expression []prevExps = exps[1];
		int prevLen = prevExps.length;
		IntArrayList seqList = new IntArrayList(expCount);
		for (int i = 0; i < prevLen; ++i) {
			if (prevExps[i] != null) {
				seqList.addInt(i);
			}
		}
		
		// �ҳ�ǰ������ֶ�����ͬ�ı�
		int next = 2;
		for (; next < tcount; ++next) {
			Expression []tmp = exps[next];
			if (tmp.length != prevLen) {
				break;
			}
			
			for (int i = 0; i < tmp.length; ++i) {
				if ((tmp[i] == null && prevExps[i] != null) || (tmp[i] != null && prevExps[i] == null)) {
					break;
				}
			}
		}
		
		Sequence []tmpSeqs = new Sequence[next];
		Expression[][] tmpExps = new Expression[next][];
		String[] tmpNames = new String[next];
		int tmpExpCount = prevExps.length;
		
		for (int i = 0; i < next; ++i) {
			Expression []curExps = new Expression[tmpExpCount];
			tmpSeqs[i] = srcs[i];
			tmpExps[i] = curExps;
			tmpNames[i] = names[i];
			Expression []srcExps = exps[i];
			for (int j = 0; j < tmpExpCount; ++j) {
				curExps[j] = srcExps[seqList.getInt(j)];
			}
		}
		
		// �Թ����ֶ���ͬ�ı�����ϣ����
		Table prevResult = hashJoin(tmpSeqs, tmpExps, tmpNames, type, ctx);
		
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil();
		ComputeStack stack = ctx.getComputeStack();
		
		for (; next < tcount; ++next) {
			// �ҳ���ǰ�����һ�����Ĺ����ֶ�
			prevExps = exps[next];
			prevLen = prevExps.length;
			seqList.clear();;
			for (int i = 0; i < prevLen; ++i) {
				if (prevExps[i] != null) {
					seqList.addInt(i);
				}
			}
			
			int keyCount = seqList.size();
			Expression []exps1 = new Expression[keyCount];
			Expression []exps2 = new Expression[keyCount];
			
			for (int j = 0; j < tmpExpCount; ++j) {
				exps1[j] = exps[0][seqList.getInt(j)];
				exps2[j] = exps[next][seqList.getInt(j)];
			}
			
			int count = keyCount + 1;
			ListBase1 [][]hashGroups = new ListBase1[hashUtil.getCapacity()][];
			Sequence value = prevResult.fieldValues(0);
			Sequence.Current current = value.new Current();
			stack.push(current);
			
			try {
				// �Ե�һ��������ϣ����
				for (int i = 1, len = value.length(); i <= len; ++i) {
					Object []keys = new Object[count];
					keys[keyCount] = prevResult.getMem(i);
					current.setCurrent(i);
					for (int k = 0; k < keyCount; ++k) {
						keys[k] = exps1[k].calculate(ctx);
					}
					
					int hash = hashUtil.hashCode(keys, keyCount);
					ListBase1 []groups = hashGroups[hash];
					if (groups == null) {
						groups = new ListBase1[2];
						hashGroups[hash] = groups;
					}

					if (groups[0] == null) {
						groups[0] = new ListBase1(INIT_GROUPSIZE);
						groups[0].add(keys);
					} else {
						int index = HashUtil.bsearch_a(groups[0], keys, keyCount);
						if (index < 1) {
							groups[0].add(-index, keys);
						} else {
							groups[0].add(index + 1, keys);
						}
					}
				}
			} finally {
				stack.pop();
			}
			
			value = srcs[next];
			current = value.new Current();
			stack.push(current);
			
			try {
				// �Ե�ǰ������ϣ����
				for (int i = 1, len = value.length(); i <= len; ++i) {
					Object []keys = new Object[count];
					keys[keyCount] = value.getMem(i);
					current.setCurrent(i);
					for (int k = 0; k < keyCount; ++k) {
						keys[k] = exps2[k].calculate(ctx);
					}
					
					int hash = hashUtil.hashCode(keys, keyCount);
					ListBase1 []groups = hashGroups[hash];
					if (groups == null) {
						groups = new ListBase1[2];
						hashGroups[hash] = groups;
					}

					if (groups[1] == null) {
						groups[1] = new ListBase1(INIT_GROUPSIZE);
						groups[1].add(keys);
					} else {
						int index = HashUtil.bsearch_a(groups[1], keys, keyCount);
						if (index < 1) {
							groups[1].add(-index, keys);
						} else {
							groups[1].add(index + 1, keys);
						}
					}
				}
			} finally {
				stack.pop();
			}
			
			// ��ÿ����ϣ��������鲢����
			Table out = new Table(new String[2]);
			for (int i = 0, len = hashGroups.length; i < len; ++i) {
				if (hashGroups[i] != null) {
					join_m(hashGroups[i], keyCount, type, out);
					hashGroups[i] = null;
				}
			}
			
			// չ���������
			String []curNames = new String[next + 1];
			System.arraycopy(names, 0, curNames, 0, next + 1);
			int len = out.length();
			prevResult = new Table(curNames, len);
			for (int i = 1; i <= len; ++i) {
				Record r = (Record)out.getMem(i);
				Record nr = prevResult.newLast();
				nr.set((Record)r.getNormalFieldValue(0));
				nr.setNormalFieldValue(next, r.getNormalFieldValue(1));
			}
		}
		
		return prevResult;
	}
	
	/**
	 * ���Ե�һ�����������ӹ���
	 * @param srcs ��������
	 * @param exps ��������ʽ����
	 * @param opt ѡ�m�����򣬲��ù鲢�������ӣ�i������������1���ܹ����ϵļ�¼��d������������1�й������ϵļ�¼
	 * @param ctx ����������
	 * @return Sequence ����1���˺�ļ�¼��ɵ�����
	 */
	public static Sequence filterJoin(Sequence[] srcs, Expression[][] exps, String opt, Context ctx) {
		if (opt.indexOf('m') != -1) {
			int count = srcs.length;
			ICursor []cursors = new ICursor[count];
			for (int i = 0; i < count; ++i) {
				cursors[i] = new MemoryCursor(srcs[i]);
			}
			
			MergeFilterCursor cs = new MergeFilterCursor(cursors, exps, opt, ctx);
			return cs.fetch();
		}
		
		ComputeStack stack = ctx.getComputeStack();
		Expression []exps0 = exps[0];
		Sequence seq = srcs[0];
		
		for (int s = 1; s < srcs.length; ++s) {
			if (seq == null || seq.length() == 0) {
				return new Sequence();
			}
			
			// �ҳ���������ʽ������null
			ArrayList<Expression> expList0 = new ArrayList<Expression>();
			ArrayList<Expression> expList = new ArrayList<Expression>();
			Expression []curExps = exps[s];
			for (int i = 0; i < curExps.length; ++i) {
				if (curExps[i] != null) {
					expList0.add(exps0[i]);
					expList.add(curExps[i]);
				}
			}
			
			int keyCount = expList0.size();
			Expression []curExps0 = new Expression[keyCount];
			curExps = new Expression[keyCount];
			expList0.toArray(curExps0);
			expList.toArray(curExps);
			Sequence result = new Sequence(seq.length());
			
			// ���ݵ�ǰѭ���ı��������ʽ��ֵ�����ɹ�ϣ����
			Sequence curSeq = srcs[s];
			Sequence.Current current = curSeq.new Current();
			stack.push(current);
			int len = curSeq.length();
			HashArraySet set = new HashArraySet(len);
			
			try {
				for (int i = 1; i <= len; ++i) {
					Object []keys = new Object[keyCount];
					current.setCurrent(i);
					
					for (int k = 0; k < keyCount; ++k) {
						keys[k] = curExps[k].calculate(ctx);
					}
					
					set.put(keys);
				}
			} finally {
				stack.pop();
			}
			
			// ѭ����һ�����е�ǰ��Ĺ�ϣ������ƥ��
			len = seq.length();
			Object []keys = new Object[keyCount];
			current = seq.new Current();
			stack.push(current);
			
			try {
				if (opt.indexOf('i') != -1) {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						for (int k = 0; k < keyCount; ++k) {
							keys[k] = curExps0[k].calculate(ctx);
						}
						
						if (set.contains(keys)) {
							result.add(seq.getMem(i));
						}
					}
				} else {
					for (int i = 1; i <= len; ++i) {
						current.setCurrent(i);
						for (int k = 0; k < keyCount; ++k) {
							keys[k] = curExps0[k].calculate(ctx);
						}
						
						if (!set.contains(keys)) {
							result.add(seq.getMem(i));
						}
					}
				}
			} finally {
				stack.pop();
			}
			
			seq = result;
		}
		
		return seq;
	}
	
	/**
	 * �α�Թ����ֶ�����������鲢����
	 * @param cursors �α�����
	 * @param names ������ֶ�������
	 * @param exps �����ֶα���ʽ����
	 * @param opt ѡ��
	 * @param ctx Context ����������
	 * @return ICursor ������α�
	 */
	public static ICursor joinx(ICursor []cursors, String []names, Expression [][]exps, String opt, Context ctx) {
		boolean isPJoin = false, isIsect = false, isDiff = false;
		if (opt != null) {
			if (opt.indexOf('p') != -1) {
				isPJoin = true;
			} else if (opt.indexOf('i') != -1) {
				isIsect = true;
			} else if (opt.indexOf('d') != -1) {
				isDiff = true;
			}
		}
		
		int count = cursors.length;
		boolean isCluster = true; // �Ƿ��м�Ⱥ�α�
		boolean isMultipath = false; // �Ƿ��Ƕ�·�α�����
		int pathCount = 1;
		
		for (int i = 0; i < count; ++i) {
			if (cursors[i] instanceof IMultipath) {
				if (i == 0) {
					isMultipath = true;
					pathCount = ((IMultipath)cursors[i]).getPathCount();
				} else if (pathCount != ((IMultipath)cursors[i]).getPathCount()) {
					isMultipath = false;
				}
			} else {
				isMultipath = false;
			}
			
			if (!(cursors[i] instanceof ClusterCursor)) {
				isCluster = false;
			}
		}
		
		if (isCluster) {
			ClusterCursor []tmp = new ClusterCursor[count];
			System.arraycopy(cursors, 0, tmp, 0, count);
			return ClusterCursor.joinx(tmp, exps, names, opt, ctx);
		} else if (isMultipath && pathCount > 1) {
			// ��·�α����ͬ���ֶΣ�ֻҪÿ��������Ӧ·�����Ӽ���
			ICursor []result = new ICursor[pathCount];
			ICursor [][]multiCursors = new ICursor[count][];
			for (int i = 0; i < count; ++i) {
				IMultipath multipath = (IMultipath)cursors[i];
				multiCursors[i] = multipath.getParallelCursors();
			}
			
			for (int i = 0; i < pathCount; ++i) {
				if (isPJoin) {
					ICursor []curs = new ICursor[count];
					for (int c = 0; c < count; ++c) {
						curs[c] = multiCursors[c][i];
					}

					result[i] = new PJoinCursor(curs, names);
				} else if (isIsect || isDiff) {
					ICursor []curs = new ICursor[count];
					for (int c = 0; c < count; ++c) {
						curs[c] = multiCursors[c][i];
					}
					
					Context tmpCtx = ctx.newComputeContext();
					Expression [][]tmpExps = Operation.dupExpressions(exps, tmpCtx);
					result[i] = new MergeFilterCursor(curs, tmpExps, opt, tmpCtx);
				} else {
					if (count == 2 && exps[0].length == 1) {
						Context tmpCtx = ctx.newComputeContext();
						Expression exp1 = Operation.dupExpression(exps[0][0], tmpCtx);
						Expression exp2 = Operation.dupExpression(exps[1][0], tmpCtx);
						result[i] = new JoinxCursor2(multiCursors[0][i], exp1, multiCursors[1][i], exp2, names, opt, tmpCtx);
					} else {
						ICursor []curs = new ICursor[count];
						for (int c = 0; c < count; ++c) {
							curs[c] = multiCursors[c][i];
						}
						
						Context tmpCtx = ctx.newComputeContext();
						Expression [][]tmpExps = Operation.dupExpressions(exps, tmpCtx);
						result[i] = new JoinxCursor(curs, tmpExps, names, opt, tmpCtx);
					}
				}
			}
			
			// ÿһ·�Ĺ����������ɶ�·�α�
			return new MultipathCursors(result, ctx);
		} else if (isPJoin) {
			return new PJoinCursor(cursors, names);
		} else if (isIsect || isDiff) {
			return new MergeFilterCursor(cursors, exps, opt, ctx);
		} else {
			if (count == 2 && exps[0].length == 1) {
				// �Թ����ֶθ���Ϊ1�������������Ż�
				return new JoinxCursor2(cursors[0], exps[0][0], cursors[1], exps[1][0], names, opt, ctx);
			} else {
				return new JoinxCursor(cursors, exps, names, opt, ctx);
			}
		}
	}
	
	public static Sequence joinx(Sequence seq, Expression [][]fields, Object []fileTable, 
			Expression[][] keys, Expression[][] exps, String[][] expNames, String fname, Context ctx, String option) {
		if (seq.length() == 0) {
			return null;
		}
		boolean hasC = option != null && option.indexOf('c') != -1;
		boolean hasNewExps = false;
		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq.new Current();
		
		//��������ÿ��f/T
		int len = seq.length();
		int fileCount =  fileTable.length;
		Sequence []seqs = new Sequence[fileCount];
		for (int i = 0; i < fileCount; i++) {
			if (exps[i] != null && exps[i].length > 0) {
				hasNewExps = true;
			}
			
			Expression []curExps = fields[i];
			if (fileTable[i] != null) {
				int pkCount = curExps.length;
				Object fileOrTable = fileTable[i];
				ColumnTableMetaData table = null;
				BFileReader reader = null;
				Sequence pkSeq = new Sequence();
				String [] refFields = null;
				
				if (fileOrTable instanceof ColumnTableMetaData) {
					table = (ColumnTableMetaData) fileOrTable;
					int fcount = keys[i].length;
					ArrayList<String> fieldList = new ArrayList<String>(fcount);
					for (int j = 0; j < fcount; j++) {
						fieldList.add(keys[i][j].toString());
					}
					for (Expression exp : exps[i]) {
						exp.getUsedFields(ctx, fieldList);
					}
					refFields = new String[fieldList.size()];
					fieldList.toArray(refFields);
				} else if (fileOrTable instanceof FileObject) {
					reader = new BFileReader((FileObject) fileOrTable);
				}
				
				//���Ҫ���ӵı���ʽֵ
				stack.push(current);
				try {
					for (int j = 1; j <= len; ++j) {
						current.setCurrent(j);
						Sequence temp = new Sequence();
						if (pkCount > 1) {
							for (int f = 0; f < pkCount; ++f) {
								temp.add(curExps[f].calculate(ctx));
							}
						} else {
							temp.add(curExps[0].calculate(ctx));
						}
						pkSeq.add(temp);
					}
				} finally {
					stack.pop();
				}
				
				//��f/T���ö�Ӧֵ
				Sequence valueSeq = null;
				if (hasC && i == 0) {}
				else pkSeq.sort("o");
				
				try {
					if (table != null) {
						valueSeq = table.finds(pkSeq, refFields);
					} else if (fileOrTable instanceof FileObject) {
						refFields = new String[pkCount];
						for (int j = 0; j < pkCount; j++) {
							refFields[j] = keys[i][j].toString();
						}
						
						reader.open();
						valueSeq = reader.iselectFields(refFields, pkSeq, null, ctx).fetch();
						reader.close();
					}
				} catch (IOException e) {
					throw new RQException(e);
				}
				seqs[i] = valueSeq;
			}
		}
		
		boolean isIsect = false, isDiff = false;
		if (!hasNewExps && option != null) {
			if (option.indexOf('i') != -1) {
				isIsect = true;
			} else if (option.indexOf('d') != -1) {
				isDiff = true;
			}
		}
		
		Operation op;
		if (isIsect) {
			op = new FilterJoin(null, fields, seqs, keys, option);
		} else if (isDiff) {
			op = new DiffJoin(null, fields, seqs, keys, option);
		} else {
			op = new Join(null, fname, fields, seqs, keys, exps, expNames, option);
		}
		
		return op.process(seq, ctx);
	}
	
	/**
	 * ����������ϣ����
	 * @param data ���
	 * @param fkName ����ֶ���
	 * @param code ά��
	 * @param exp ά����������ʽ
	 * @param opt ѡ��
	 * @param ctx ����������
	 */
	public static void hashSwitch(Sequence data, String fkName, Sequence code,
								  Expression exp, String opt, Context ctx) {
		if (data.length() == 0) {
			return;
		}
		
		// ȡԴ�������ݽṹ������ֶε����
		DataStruct ds = data.dataStruct();
		if (ds == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}
		
		int fkIndex = ds.getFieldIndex(fkName);
		if (fkIndex == -1) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(fkName + mm.getMessage("ds.fieldNotExist"));
		}
		
		boolean isIsect = false, isDiff = false, isLeft = false;
		if (opt != null) {
			if (opt.indexOf('i') != -1) {
				// ������
				isIsect = true;
			} else if (opt.indexOf('d') != -1) {
				// ����
				isDiff = true;
			} else if (opt.indexOf('1') != -1) {
				// �����ӣ��Ҳ���F��Ӧֵʱ�����������ݽṹ���ɿ�ֵ���������⣩��¼��Ӧ
				isLeft = true;
			}
		}

		if (exp == null || !(exp.getHome() instanceof CurrentSeq)) { // #
			IndexTable indexTable = code.getIndexTable(exp, ctx);
			if (indexTable == null) {
				indexTable = IndexTable.instance(code, exp, ctx);
			}
			
			if (isDiff) {
				for (int i = 1, len = data.length(); i <= len; ++i) {
					Record r = (Record)data.getMem(i);
					Object key = r.getNormalFieldValue(fkIndex);
					Object obj = indexTable.find(key);
					// �Ҳ���ʱ����Դֵ
					if (obj != null) {
						r.setNormalFieldValue(fkIndex, null);
					}				}
			} else if (isLeft) {
				DataStruct codeDs = code.dataStruct();
				if (codeDs == null) {
					MessageManager mm = EngineMessage.get();
					throw new RQException(mm.getMessage("engine.needPurePmt"));
				}
				
				int keySeq = -1;
				if (exp != null) {
					keySeq = codeDs.getFieldIndex(exp.getIdentifierName());
				}
				
				if (keySeq == -1) {
					int []pks = codeDs.getPKIndex();
					if (pks != null && pks.length == 1) {
						keySeq = pks[0];
					}
				}
				
				if (keySeq == -1) {
					keySeq = 0;
				}
				
				for (int i = 1, len = data.length(); i <= len; ++i) {
					Record r = (Record)data.getMem(i);
					Object key = r.getNormalFieldValue(fkIndex);
					Object obj = indexTable.find(key);
					if (obj != null) {
						r.setNormalFieldValue(fkIndex, obj);
					} else {
						Record record = new Record(codeDs);
						record.setNormalFieldValue(keySeq, key);
						r.setNormalFieldValue(fkIndex, record);
					}
				}
			} else {
				for (int i = 1, len = data.length(); i <= len; ++i) {
					Record r = (Record)data.getMem(i);
					Object key = r.getNormalFieldValue(fkIndex);
					Object obj = indexTable.find(key);
					r.setNormalFieldValue(fkIndex, obj);
				}
			}
		} else {
			// ���ά������������ʽ��#����ô�����ֵʵ���϶�Ӧά����¼����ţ�ֱ�������ȡ��ά���ļ�¼
			int codeLen = code.length();
			for (int i = 1, len = data.length(); i <= len; ++i) {
				Record r = (Record)data.getMem(i);
				Object val = r.getNormalFieldValue(fkIndex);
				if (val instanceof Number) {
					int seq = ((Number)val).intValue();
					if (isDiff) {
						// �Ҳ���ʱ����Դֵ
						if (seq > 0 && seq <= codeLen) {
							r.setNormalFieldValue(fkIndex, null);
						}
					} else {
						if (seq > 0 && seq <= codeLen) {
							r.setNormalFieldValue(fkIndex, code.getMem(seq));
						} else {
							r.setNormalFieldValue(fkIndex, null);
						}
					}
				}
			}
		}

		if (isIsect || isDiff) {
			data.deleteNullFieldRecord(fkIndex);
		}
	}
	
	/**
	 * ���ڴ�������������ܴ��ȡ���������������ʱȷ��ÿ��ȡ���ټ�¼
	 * @param cursor �α�
	 * @return ����
	 */
	public static Sequence tryFetch(ICursor cursor) {
		Runtime rt = Runtime.getRuntime();
		EnvUtil.runGC(rt);
		
		final int baseCount = ICursor.INITSIZE;
		Sequence seq = cursor.fetch(baseCount);
		if (seq == null || seq.length() == 0) {
			return null;
		}

		while (EnvUtil.memoryTest(rt, seq)) {
			Sequence seq2 = cursor.fetch(baseCount);
			if (seq2 == null || seq2.length() == 0) {
				break;
			} else {
				seq.getMems().addAll(seq2.getMems());
			}
		}

		return seq;
	}
	
	/**
	 * ���α�����������
	 * @param cursor �α�
	 * @param exps �����ֶα���ʽ����
	 * @param ctx ����������
	 * @param capacity �ڴ����ܹ�����ļ�¼�������û���������Զ�����һ��
	 * @param opt ѡ�� 0��null�����
	 * @return �ź�����α�
	 */
	public static ICursor sortx(ICursor cursor, Expression[] exps, Context ctx, int capacity, String opt) {
		int fcount = exps.length;
		ArrayList<ICursor> cursorList = new ArrayList<ICursor>();
		
		Sequence table;
		if (capacity <= 1) {
			// �����ܵĶ�ȡ���ݣ��������Լ�����ʱ�ļ�������
			// ֮��ÿ��ȡ�����������������
			table = tryFetch(cursor);
			if (table != null) {
				capacity = table.length();
			}
		} else {
			table = cursor.fetch(capacity);
		}
		
		MessageManager mm = EngineMessage.get();
		String msg = mm.getMessage("engine.createTmpFile");
		Expression[] tempExps = exps.clone();
		
		while (table != null && table.length() > 0) {
			// �ֶα���ʽ������ʱΪ�������Ż��ᱣ����¼��ָ��
			// Ϊ�����´�ȡ��ǰ�ܹ��ͷ�ǰһ�ε����ݣ��ȸ����±���ʽ���ź�������ͷű���ʽ
			for (int i = 0, len = tempExps.length; i < len; i++) {
				tempExps[i] = exps[i].newExpression(ctx);
			}
			
			Sequence sequence;
			if (fcount == 1) {
				sequence = table.sort(tempExps[0], null, opt, ctx);
			} else {
				sequence = table.sort(tempExps, null, opt, ctx);
			}

			// �Ƿ�Դ���ͱ���ʽ
			table = null;
			for (int i = 0, len = tempExps.length; i < len; i++) {
				tempExps[i] = null;
			}
			
			// ������ʱ�ļ�
			FileObject fo = FileObject.createTempFileObject();
			Logger.info(msg + fo.getFileName());
			
			// ���ź��������д����ʱ���ļ�
			fo.exportSeries(sequence, "b", null);
			sequence = null;
			BFileCursor bfc = new BFileCursor(fo, null, "x", ctx);
			cursorList.add(bfc);

			// ����ȡ����
			table = cursor.fetch(capacity);
		}

		int size = cursorList.size();
		if (size == 0) {
			//return null;
			return new MemoryCursor(null);
		} else if (size == 1) {
			return (ICursor)cursorList.get(0);
		} else {
			// ����ʱ�ļ����鲢
			int bufSize = Env.getMergeFileBufSize(size);
			for (int i = 0; i < size; ++i) {
				BFileCursor bfc = (BFileCursor)cursorList.get(i);
				bfc.setFileBufferSize(bufSize);
			}
			
			ICursor []cursors = new ICursor[size];
			cursorList.toArray(cursors);
			if (opt == null || opt.indexOf('0') == -1) {
				return new MergesCursor(cursors, exps, ctx);
			} else {
				return new MergesCursor(cursors, exps, "0", ctx);
			}
		}
	}

	/**
	 * ������������ֶ�ֵ��ͬ�ļ�¼��ֵ��ͬ��ͬ��
	 * ��ֵ��ͬ�ļ�¼���浽һ����ʱ�ļ���Ȼ��ÿ����ʱ�ļ���������
	 * @param cursor �α�
	 * @param exps �������ʽ
	 * @param gexp �����ʽ
	 * @param ctx ����������
	 * @param opt ѡ��
	 * @return
	 */
	public static ICursor sortx(ICursor cursor, Expression[] exps, Expression gexp, Context ctx, String opt) {
		Sequence seq = tryFetch(cursor);
		if (seq == null || seq.length() == 0) {
			return null;
		}
		
		final int fetchCount = seq.length();
		DataStruct ds = seq.dataStruct();
		if (ds == null) {
			MessageManager mm = EngineMessage.get();
			throw new RQException(mm.getMessage("engine.needPurePmt"));
		}
		
		// ��ֵ�����ļ�ӳ�����ÿһ����ֵ��Ӧһ�����ļ�
		TreeMap<Object, BFileWriter> map = new TreeMap<Object, BFileWriter>();
		MessageManager mm = EngineMessage.get();
		String msg = mm.getMessage("engine.createTmpFile");
		
		try {
			while (true) {
				// �����а������ʽ����
				Sequence groups = seq.group(gexp, null, ctx);
				int gcount = groups.length();
				
				for (int i = 1; i <= gcount; ++i) {
					Sequence group = (Sequence)groups.getMem(i);
					Object gval = group.calc(1, gexp, ctx);
					
					// ����ֵ�ҵ���Ӧ�ļ��ļ���д��
					BFileWriter writer = map.get(gval);
					if (writer == null) {
						FileObject fo = FileObject.createTempFileObject();
						Logger.info(msg + fo.getFileName());
						writer = new BFileWriter(fo, null);
						writer.prepareWrite(ds, false);
						map.put(gval, writer);
					}
					
					writer.write(group);
				}
				
				// �ͷ����ã��������Ա���������
				seq = null;
				groups = null;
				
				seq = cursor.fetch(fetchCount);
				if (seq == null || seq.length() == 0) {
					break;
				}
			}
		} catch (IOException e) {
			Collection<BFileWriter> writers = map.values();
			Iterator<BFileWriter> itr = writers.iterator();
			while (itr.hasNext()) {
				BFileWriter writer = itr.next();
				writer.close();
				writer.getFile().delete();
			}
			
			throw new RQException(e);
		}
		
		int size = map.size();
		FileObject []files = new FileObject[size];
		int index = 0;
		Collection<BFileWriter> writers = map.values();
		Iterator<BFileWriter> itr = writers.iterator();
		
		// д��ɣ��رռ��ļ�
		while (itr.hasNext()) {
			BFileWriter writer = itr.next();
			writer.close();
			files[index++] = writer.getFile();
		}
		
		return new SortxCursor(files, exps, ds, ctx);
	}
	
	/**
	 * �ù�ϣ�������������еĲ
	 * @param seq1 ����
	 * @param seq2 ����
	 * @return �����
	 */
	public static Sequence diff(Sequence seq1, Sequence seq2) {
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		// ������2���ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		for (int i = 1; i <= len2; ++i) {
			Object val = mems2.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] == null) {
				groups[hash] = new ListBase1(INIT_GROUPSIZE);
				groups[hash].add(val);
			} else {
				int index = groups[hash].binarySearch(val);
				if (index < 1) {
					groups[hash].add(-index, val);
				} else {
					groups[hash].add(index, val);
				}
			}
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		// ��������1��Ԫ�أ�Ȼ����ݹ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
		for (int i = 1; i <= len1; ++i) {
			Object val = mems1.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] == null) {
				result.add(val);
			} else {
				int index = groups[hash].binarySearch(val);
				if (index < 1) {
					result.add(val);
				} else {
					groups[hash].remove(index);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * �ù�ϣ�������������ж�ָ������ʽ�Ĳ
	 * @param seq1 ����
	 * @param seq2 ����
	 * @param exps ����ʽ����
	 * @param ctx ����������
	 * @return �����
	 */
	public static Sequence diff(Sequence seq1, Sequence seq2, Expression []exps, Context ctx) {
		if (exps == null) {
			return diff(seq1, seq2);
		}
		
		int keyCount = exps.length;
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		// ������2��ָ������ʽ�ļ��������ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq2.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len2; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						groups[hash].add(-index, keys);
					} else {
						groups[hash].add(index, keys);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		current = seq1.new Current();
		stack.push(current);

		try {
			// ��������1��Ȼ����ݱ���ʽ�������Ĺ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
			for (int i = 1; i <= len1; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					result.add(mems1.get(i));
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						result.add(mems1.get(i));
					} else {
						groups[hash].remove(index);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		return result;
	}

	/**
	 * �ù�ϣ�������������еĲ���
	 * @param seq1 ����
	 * @param seq2 ����
	 * @return ��������
	 */
	public static Sequence union(Sequence seq1, Sequence seq2) {
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		
		// ������2���ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len1 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		for (int i = 1; i <= len1; ++i) {
			Object val = mems1.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] == null) {
				groups[hash] = new ListBase1(INIT_GROUPSIZE);
				groups[hash].add(val);
			} else {
				int index = groups[hash].binarySearch(val);
				if (index < 1) {
					groups[hash].add(-index, val);
				} else {
					groups[hash].add(index, val);
				}
			}
		}
		
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		Sequence result = new Sequence(len1 + len2);
		result.addAll(seq1);
		
		// ��������1��Ԫ�أ�Ȼ����ݹ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
		for (int i = 1; i <= len2; ++i) {
			Object val = mems2.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] == null) {
				result.add(val);
			} else {
				int index = groups[hash].binarySearch(val);
				if (index < 1) {
					result.add(val);
				} else {
					groups[hash].remove(index);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * �ù�ϣ�������������ж�ָ������ʽ�Ĳ���
	 * @param seq1 ����
	 * @param seq2 ����
	 * @param exps ����ʽ����
	 * @param ctx ����������
	 * @return ��������
	 */
	public static Sequence union(Sequence seq1, Sequence seq2, Expression []exps, Context ctx) {
		if (exps == null) {
			return union(seq1, seq2);
		}
		
		int keyCount = exps.length;
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		
		// ������2��ָ������ʽ�ļ��������ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len1 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq1.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len1; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						groups[hash].add(-index, keys);
					} else {
						groups[hash].add(index, keys);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		Sequence result = new Sequence(len1 + len2);
		result.addAll(seq1);
		
		current = seq2.new Current();
		stack.push(current);

		try {
			// ��������1��Ȼ����ݱ���ʽ�������Ĺ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
			for (int i = 1; i <= len2; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					result.add(mems2.get(i));
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						result.add(mems2.get(i));
					} else {
						groups[hash].remove(index);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		return result;
	}

	/**
	 * �ù�ϣ�������������еĽ���
	 * @param seq1 ����
	 * @param seq2 ����
	 * @return ��������
	 */
	public static Sequence isect(Sequence seq1, Sequence seq2) {
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		// ������2���ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		for (int i = 1; i <= len2; ++i) {
			Object val = mems2.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] == null) {
				groups[hash] = new ListBase1(INIT_GROUPSIZE);
				groups[hash].add(val);
			} else {
				int index = groups[hash].binarySearch(val);
				if (index < 1) {
					groups[hash].add(-index, val);
				} else {
					groups[hash].add(index, val);
				}
			}
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		// ��������1��Ԫ�أ�Ȼ����ݹ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
		for (int i = 1; i <= len1; ++i) {
			Object val = mems1.get(i);
			int hash = hashUtil.hashCode(val);
			if (groups[hash] != null) {
				int index = groups[hash].binarySearch(val);
				if (index > 0) {
					result.add(val);
					groups[hash].remove(index);
				}
			}
		}
		
		return result;
	}
	
	/**
	 * �ù�ϣ�������������ж�ָ������ʽ�Ľ���
	 * @param seq1 ����
	 * @param seq2 ����
	 * @param exps ����ʽ����
	 * @param ctx ����������
	 * @return ��������
	 */
	public static Sequence isect(Sequence seq1, Sequence seq2, Expression []exps, Context ctx) {
		if (exps == null) {
			return isect(seq1, seq2);
		}
		
		int keyCount = exps.length;
		ListBase1 mems2 = seq2.getMems();
		int len2 = mems2.size();
		
		// ������2��ָ������ʽ�ļ��������ɹ�ϣ��
		final int INIT_GROUPSIZE = HashUtil.getInitGroupSize();
		HashUtil hashUtil = new HashUtil((int)(len2 * 1.2));
		ListBase1 []groups = new ListBase1[hashUtil.getCapacity()];

		ComputeStack stack = ctx.getComputeStack();
		Sequence.Current current = seq2.new Current();
		stack.push(current);

		try {
			for (int i = 1; i <= len2; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}

				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] == null) {
					groups[hash] = new ListBase1(INIT_GROUPSIZE);
					groups[hash].add(keys);
				} else {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index < 1) {
						groups[hash].add(-index, keys);
					} else {
						groups[hash].add(index, keys);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		ListBase1 mems1 = seq1.getMems();
		int len1 = mems1.size();
		Sequence result = new Sequence(len1);
		
		current = seq1.new Current();
		stack.push(current);

		try {
			// ��������1��Ȼ����ݱ���ʽ�������Ĺ�ϣֵ������2�Ĺ�ϣ���в����Ƿ�����ͬ��Ԫ��
			for (int i = 1; i <= len1; ++i) {
				Object []keys = new Object[keyCount];
				current.setCurrent(i);
				for (int c = 0; c < keyCount; ++c) {
					keys[c] = exps[c].calculate(ctx);
				}
	
				int hash = hashUtil.hashCode(keys, keyCount);
				if (groups[hash] != null) {
					int index = HashUtil.bsearch_a(groups[hash], keys, keyCount);
					if (index > 0) {
						result.add(mems1.get(i));
						groups[hash].remove(index);
					}
				}
			}
		} finally {
			stack.pop();
		}
		
		return result;
	}
	
	/**
	 * ȡcount��ʹexp����ֵ��С��Ԫ�ص�getExp����ֵ
	 * @param cursor �α�
	 * @param count ����
	 * @param exp �Ƚϱ���ʽ
	 * @param getExp ����ֵ����ʽ
	 * @param ctx ����������
	 * @return Object
	 */
	public static Object top(ICursor cursor, int count, Expression exp, Expression getExp, Context ctx) {
		// ʹ�ö�ȡǰ����
		ArrayComparator comparator = new ArrayComparator(1);
		MinHeap heap = new MinHeap(count, comparator);
		
		while (true) {
			Sequence src = cursor.fuzzyFetch(ICursor.FETCHCOUNT);
			if (src == null || src.length() == 0) {
				break;
			}
			
			src = src.calc(getExp, ctx);
			if (src.getMem(1) instanceof Sequence) {
				src = src.conj(null);
			}
			
			// ���������ֶ�ֵ�������ӵ�С������
			Sequence v = src.calc(exp, ctx);
			for (int i = 1, len = src.length(); i <= len; ++i) {
				Object []vals = new Object[2];
				vals[0] = v.getMem(i);
				vals[1] = src.getMem(i);
				heap.insert(vals);
			}
		}
		
		// �Խ����������
		Object []objs = heap.toArray();
		Arrays.sort(objs, comparator);
		
		int size = objs.length;
		Sequence seq = new Sequence(size);
		for (int i = 0; i < size; ++i) {
			Object []tmp = (Object[])objs[i];
			seq.add(tmp[1]);
		}
		
		return seq;
	}
		
	/**
	 * �����������е����ֵ������ͬ��Ԫ����ɵ�����
	 * @param seq1 ����
	 * @param seq2 ����
	 * @return �������
	 */
	public static Sequence xor(Sequence seq1, Sequence seq2) {
		Sequence s1 = diff(seq1, seq2);
		Sequence s2 = diff(seq2, seq1);
		s1.addAll(s2);
		return s1;
	}
	
	/**
	 * �Ȱ�gexp�����ݽ��з��飬ͬ��Ļ���һ��д��һ����ʱ�ļ�������ٶ�ÿ����ʱ�ļ����ж��λ���
	 * �����ֶ���ͬ�ļ�¼gexpҲҪ��ͬ��gexp�Ƿ����ֶεĴ����
	 * @param cursor �α�
	 * @param gexp ��������ʽ
	 * @param exps �������ʽ����
	 * @param names	�����ֶ�������
	 * @param calcExps ���ܱ���ʽ����
	 * @param calcNames	�����ֶ�������
	 * @param opt ѡ��
	 * @param ctx ����������
	 * @return �������α�
	 */
	public static ICursor groupx_g(ICursor cursor, Expression gexp, Expression[] exps, String []names,
			   Expression[] calcExps, String []calcNames, String opt, Context ctx) {
		if (cursor instanceof MultipathCursors) {
			//  ��·�α���ö��̷߳���
			return groupx_g((MultipathCursors)cursor, gexp, exps, names, calcExps, calcNames, ctx);
		}
		
		final int fetchCount = ICursor.INITSIZE;
		MessageManager mm = EngineMessage.get();
		String msg = mm.getMessage("engine.createTmpFile");
		TreeMap<Object, BFileWriter> map = new TreeMap<Object, BFileWriter>();
		
		try {
			// �����α�����
			while (true) {
				Sequence seq = cursor.fetch(fetchCount);
				if (seq == null || seq.length() == 0) {
					break;
				}
				
				// ����������ʽ�����ݽ��з���
				Sequence groups = seq.group(gexp, null, ctx);
				int gcount = groups.length();
				for (int i = 1; i <= gcount; ++i) {
					// ��ÿ�����������״λ���
					Sequence group = (Sequence)groups.getMem(i);
					IGroupsResult gresult = IGroupsResult.instance(exps, names, calcExps, calcNames, null, ctx);
					gresult.push(group, ctx);
					group = gresult.getTempResult();
					
					// �ҵ���ǰ������Ӧ����ʱ�ļ������״λ��ܽ��׷�ӵ���ʱ�ļ���
					Object gval = group.calc(1, gexp, ctx);
					BFileWriter writer = map.get(gval);
					if (writer == null) {
						FileObject fo = FileObject.createTempFileObject();
						Logger.info(msg + fo.getFileName());
						writer = new BFileWriter(fo, null);
						writer.prepareWrite(gresult.getResultDataStruct(), false);
						map.put(gval, writer);
					}
					
					writer.write(group);
				}
			}
		} catch (IOException e) {
			// ���쳣����ʱ�رղ�ɾ����ʱ�ļ�
			Collection<BFileWriter> writers = map.values();
			Iterator<BFileWriter> itr = writers.iterator();
			while (itr.hasNext()) {
				BFileWriter writer = itr.next();
				writer.close();
				writer.getFile().delete();
			}
			
			throw new RQException(e);
		}
		
		int size = map.size();
		if (size == 0) {
			return null;
		}
		
		// д��ɣ��رռ��ļ�		
		FileObject []files = new FileObject[size];
		int index = 0;
		Collection<BFileWriter> writers = map.values();
		Iterator<BFileWriter> itr = writers.iterator();
		while (itr.hasNext()) {
			BFileWriter writer = itr.next();
			writer.close();
			files[index++] = writer.getFile();
		}
		
		// ����һ����ÿ����ʱ�ļ����з�����α�
		return new GroupxnCursor(files, exps, names, calcExps, calcNames, ctx);
	}
	
	private static ICursor groupx_g(MultipathCursors mcs, Expression gexp, Expression[] exps, String []names,
			   Expression[] calcExps, String []calcNames, Context ctx) {
		final int fetchCount = ICursor.INITSIZE;
		ICursor []cursors = mcs.getParallelCursors();
		int cursorCount = cursors.length;
		TreeMap<Object, BFileWriter> map = new TreeMap<Object, BFileWriter>();
		
		// ���ɷ��������ύ���̳߳�
		ThreadPool pool = ThreadPool.newInstance(cursorCount);
		Exception exception = null;

		try {
			GroupxJob []jobs = new GroupxJob[cursorCount];
			for (int i = 0; i < cursorCount; ++i) {
				Context tmpCtx = ctx.newComputeContext();
				Expression tmpGroupExp = Operation.dupExpression(gexp, tmpCtx);
				Expression []tmpExps = Operation.dupExpressions(exps, tmpCtx);
				Expression []tmpCalcExps = Operation.dupExpressions(calcExps, tmpCtx);
				
				jobs[i] = new GroupxJob(cursors[i], tmpGroupExp, tmpExps, names, 
						tmpCalcExps, calcNames, tmpCtx, fetchCount, map);
				pool.submit(jobs[i]);
			}
	
			// �ȴ���������ִ�����
			for (int i = 0; i < cursorCount; ++i) {
				try {
					jobs[i].join();
				} catch (RuntimeException e) {
					exception = e;
				}
			}
		} finally {
			pool.shutdown();
		}
		
		// ���쳣����ʱ�رղ�ɾ����ʱ�ļ�
		if (exception != null) {
			Collection<BFileWriter> writers = map.values();
			Iterator<BFileWriter> itr = writers.iterator();
			while (itr.hasNext()) {
				BFileWriter writer = itr.next();
				writer.close();
				writer.getFile().delete();
			}
			
			if (exception instanceof RQException) {
				throw (RQException)exception;
			} else {
				throw new RQException(exception);
			}
		}

		int size = map.size();
		if (size == 0) {
			return null;
		}
		
		// д��ɣ��رռ��ļ�		
		FileObject []files = new FileObject[size];
		int index = 0;
		Collection<BFileWriter> writers = map.values();
		Iterator<BFileWriter> itr = writers.iterator();
		while (itr.hasNext()) {
			BFileWriter writer = itr.next();
			writer.close();
			files[index++] = writer.getFile();
		}
		
		// ����һ����ÿ����ʱ�ļ����з�����α�
		return new GroupxnCursor(files, exps, names, calcExps, calcNames, ctx);
	}

	/**
	 * ���еĵ�һ���ֶ�Ϊ��������#1 / capacity + 1�ֳ�������
	 * @param seq ����
	 * @param capacity ����
	 * @return ������
	 */
	public static Sequence group_n(Sequence seq, int capacity) {
		ListBase1 mems = seq.getMems();
		int size = mems.size();
		Sequence result = new Sequence(size / 4); // ���������
		ListBase1 resultMems = result.getMems();
		int len = 0;
		Record r;
		Object value;
		
		for (int i = 1; i <= size; ++i) {
			r = (Record)mems.get(i);
			value = r.getNormalFieldValue(0);
			if (!(value instanceof Number)) {
				MessageManager mm = EngineMessage.get();
				throw new RQException("group: " + mm.getMessage("engine.needIntExp"));
			}

			int index = ((Number)value).intValue() / capacity + 1;
			if (index > len) {
				resultMems.ensureCapacity(index);
				for (int j = len; j < index; ++j) {
					resultMems.add(new Sequence(7));
				}

				len = index;
			} else if (index < 1) {
				MessageManager mm = EngineMessage.get();
				throw new RQException(index + mm.getMessage("engine.indexOutofBound"));
			}

			Sequence group = (Sequence)resultMems.get(index);
			group.add(r);
		}
		
		return result;
	}
	
	/**
	 * �����ֶ�ֵΪ����ţ��ѷ����ֶΰ�capacity���й�ϣ����ϣֵ��ͬ��д��ͬһ����ʱ�ļ�������ٶ�ÿ����ʱ�ļ����ж��λ���
	 * @param cursor �α�
	 * @param exps �������ʽ����
	 * @param names	�����ֶ�������
	 * @param calcExps ���ܱ���ʽ����
	 * @param calcNames	�����ֶ�������
	 * @param ctx ����������
	 * @param capacity �ڴ��ܹ���ŵķ�����������
	 * @return �������α�
	 */
	public static ICursor groupx_n(ICursor cursor, Expression[] exps, String []names,
			   Expression[] calcExps, String []calcNames, Context ctx, int capacity) {
		if (cursor instanceof MultipathCursors) {
			//  ��·�α���ö��̷߳���
			return groupx_n((MultipathCursors)cursor, exps, names, calcExps, calcNames, ctx, capacity);
		}
		
		final int fetchCount = ICursor.INITSIZE;
		MessageManager mm = EngineMessage.get();
		String msg = mm.getMessage("engine.createTmpFile");
		TreeMap<Object, BFileWriter> map = new TreeMap<Object, BFileWriter>();
		
		try {
			// �����α�����
			while (true) {
				Sequence seq = cursor.fetch(fetchCount);
				if (seq == null || seq.length() == 0) {
					break;
				}
				
				// �Ե�ǰ���ݽ����״λ���
				IGroupsResult gresult = IGroupsResult.instance(exps, names, calcExps, calcNames, null, ctx);
				gresult.push(seq, ctx);
				seq = gresult.getTempResult();
				
				// ���ÿ����ʱ�ļ�Ӧ�ô�ŵķ���
				Sequence groups = group_n(seq, capacity);
				int gcount = groups.length();
				for (int i = 1; i <= gcount; ++i) {
					Sequence group = (Sequence)groups.getMem(i);
					if (group.length() == 0) {
						continue;
					}
					
					// ���״η�����д����Ӧ����ʱ�ļ�
					Integer gval = new Integer(i);
					BFileWriter writer = map.get(gval);
					if (writer == null) {
						FileObject fo = FileObject.createTempFileObject();
						Logger.info(msg + fo.getFileName());
						writer = new BFileWriter(fo, null);
						writer.prepareWrite(gresult.getResultDataStruct(), false);
						map.put(gval, writer);
					}
					
					
					writer.write(group);
				}
			}
		} catch (IOException e) {
			// ���쳣����ʱ�رղ�ɾ����ʱ�ļ�
			Collection<BFileWriter> writers = map.values();
			Iterator<BFileWriter> itr = writers.iterator();
			while (itr.hasNext()) {
				BFileWriter writer = itr.next();
				writer.close();
				writer.getFile().delete();
			}
			
			throw new RQException(e);
		}
		
		int size = map.size();
		if (size == 0) {
			return null;
		}
		
		// д��ɣ��رռ��ļ�		
		FileObject []files = new FileObject[size];
		int index = 0;
		Collection<BFileWriter> writers = map.values();
		Iterator<BFileWriter> itr = writers.iterator();
		while (itr.hasNext()) {
			BFileWriter writer = itr.next();
			writer.close();
			files[index++] = writer.getFile();
		}
		
		// ����һ����ÿ����ʱ�ļ����з�����α�
		return new GroupxnCursor(files, exps, names, calcExps, calcNames, ctx);
	}

	private static ICursor groupx_n(MultipathCursors mcs, Expression[] exps, String []names,
			   Expression[] calcExps, String []calcNames, Context ctx, int capacity) {
		ICursor []cursors = mcs.getParallelCursors();
		int cursorCount = cursors.length;
		TreeMap<Object, BFileWriter> map = new TreeMap<Object, BFileWriter>();
		int fetchCount = capacity / cursorCount;
		
		// ���ɷ��������ύ���̳߳�
		ThreadPool pool = ThreadPool.newInstance(cursorCount);
		Exception exception = null;

		try {
			GroupxJob []jobs = new GroupxJob[cursorCount];
			for (int i = 0; i < cursorCount; ++i) {
				Context tmpCtx = ctx.newComputeContext();
				Expression []tmpExps = Operation.dupExpressions(exps, tmpCtx);
				Expression []tmpCalcExps = Operation.dupExpressions(calcExps, tmpCtx);
				
				jobs[i] = new GroupxJob(cursors[i], tmpExps, names, 
						tmpCalcExps, calcNames, tmpCtx, capacity, fetchCount, map);
				pool.submit(jobs[i]);
			}
	
			// �ȴ���������ִ�����
			for (int i = 0; i < cursorCount; ++i) {
				try {
					jobs[i].join();
				} catch (RuntimeException e) {
					exception = e;
				}
			}
		} finally {
			pool.shutdown();
		}
		
		// ���쳣����ʱ�رղ�ɾ����ʱ�ļ�
		if (exception != null) {
			Collection<BFileWriter> writers = map.values();
			Iterator<BFileWriter> itr = writers.iterator();
			while (itr.hasNext()) {
				BFileWriter writer = itr.next();
				writer.close();
				writer.getFile().delete();
			}
			
			if (exception instanceof RQException) {
				throw (RQException)exception;
			} else {
				throw new RQException(exception);
			}
		}

		int size = map.size();
		if (size == 0) {
			return null;
		}
		
		// д��ɣ��رռ��ļ�		
		FileObject []files = new FileObject[size];
		int index = 0;
		Collection<BFileWriter> writers = map.values();
		Iterator<BFileWriter> itr = writers.iterator();
		while (itr.hasNext()) {
			BFileWriter writer = itr.next();
			writer.close();
			files[index++] = writer.getFile();
		}
		
		// ����һ����ÿ����ʱ�ļ����з�����α�
		return new GroupxnCursor(files, exps, names, calcExps, calcNames, ctx);
	}
	
	/**
	 * ȡ�α��Ӧ����������ȡ�����򷵻ؿ�
	 * @param cs �α�
	 * @return TableMetaData
	 */
	public static TableMetaData getTableMetaData(ICursor cs) {
		if (cs instanceof IDWCursor) {
			return ((IDWCursor)cs).getTableMetaData();
		} else if (cs instanceof MultipathCursors) {
			MultipathCursors mcs = (MultipathCursors)cs;
			ICursor []cursors = mcs.getCursors();
			return getTableMetaData(cursors[0]);
		} else if (cs instanceof MergeCursor2) {
			MergeCursor2 mc = (MergeCursor2)cs;
			return getTableMetaData(mc.getCursor1());
		} else {
			return null;
		}
	}
	
	/**
	 * ���α갴ָ������ʽ���鲢�������α�
	 * @param cursors �α�����
	 * @param exps ����ʽ����
	 * @param opt ѡ�
	 * @param ctx
	 * @return
	 */
	public static ICursor merge(ICursor []cursors, Expression []exps, String opt, Context ctx) {
		// ������������㲢�ҹ鲢����ʽ���ֶεĻ������Ż�
		DataStruct ds = null;
		if (opt == null || (opt.indexOf('u') == -1 && opt.indexOf('i') == -1 && opt.indexOf('d') == -1 && opt.indexOf('x') == -1)) {
			ds = CursorUtil.getDataStruct(cursors[0]);
			for (int i = 1, count = cursors.length; ds != null && i < count; ++i) {
				if (!ds.isCompatible(CursorUtil.getDataStruct(cursors[i]))) {
					ds = null;
					break;
				}
			}
		}
		
		int []fields = null;
		if (ds != null) {
			if (exps == null) {
				String []sortFields = cursors[0].getSortFields();
				if (sortFields != null) {
					int fcount = sortFields.length;
					fields = new int[fcount];
					for (int f = 0; f < fcount; ++f) {
						fields[f] = ds.getFieldIndex(sortFields[f]);
					}
				} else {
					int fcount = ds.getFieldCount();
					fields = new int[fcount];
					for (int f = 0; f < fcount; ++f) {
						fields[f] = f;
					}
				}
			} else {
				int fcount = exps.length;
				fields = new int[fcount];
				for (int f = 0; f < fcount; ++f) {
					fields[f] = exps[f].getFieldIndex(ds);
					if (fields[f] < 0) {
						fields = null;
						break;
					}
				}
			}
		} else if (exps == null) {
			Expression exp = new Expression("~.v()");
			exps = new Expression[]{ exp };
		}
		
		if (fields != null) {
			if (cursors.length == 2) {
				return new MergeCursor2(cursors[0], cursors[1], fields, opt, ctx);
			} else {
				return new MergeCursor(cursors, fields, opt, ctx);
			}
		} else {
			return new MergesCursor(cursors, exps, opt, ctx);
		}
	}
}