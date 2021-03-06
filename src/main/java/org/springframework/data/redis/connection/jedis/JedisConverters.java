/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.redis.connection.jedis;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.DefaultTuple;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisListCommands.Position;
import org.springframework.data.redis.connection.RedisServer;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.connection.RedisZSetCommands.Range.Boundary;
import org.springframework.data.redis.connection.RedisZSetCommands.Tuple;
import org.springframework.data.redis.connection.SortParameters;
import org.springframework.data.redis.connection.SortParameters.Order;
import org.springframework.data.redis.connection.SortParameters.Range;
import org.springframework.data.redis.connection.convert.Converters;
import org.springframework.data.redis.connection.convert.ListConverter;
import org.springframework.data.redis.connection.convert.MapConverter;
import org.springframework.data.redis.connection.convert.SetConverter;
import org.springframework.data.redis.connection.convert.StringToRedisClientInfoConverter;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.core.types.RedisClientInfo;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import redis.clients.jedis.BinaryClient.LIST_POSITION;
import redis.clients.jedis.BitOP;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.SortingParams;
import redis.clients.util.SafeEncoder;

/**
 * Jedis type converters.
 * 
 * @author Jennifer Hickey
 * @author Christoph Strobl
 * @author Thomas Darimont
 * @author Jungtaek Lim
 * @author Mark Paluch
 */
abstract public class JedisConverters extends Converters {

	private static final Converter<String, byte[]> STRING_TO_BYTES;
	private static final ListConverter<String, byte[]> STRING_LIST_TO_BYTE_LIST;
	private static final SetConverter<String, byte[]> STRING_SET_TO_BYTE_SET;
	private static final MapConverter<String, byte[]> STRING_MAP_TO_BYTE_MAP;
	private static final SetConverter<redis.clients.jedis.Tuple, Tuple> TUPLE_SET_TO_TUPLE_SET;
	private static final Converter<Exception, DataAccessException> EXCEPTION_CONVERTER = new JedisExceptionConverter();
	private static final Converter<String[], List<RedisClientInfo>> STRING_TO_CLIENT_INFO_CONVERTER = new StringToRedisClientInfoConverter();
	private static final Converter<redis.clients.jedis.Tuple, Tuple> TUPLE_CONVERTER;
	private static final ListConverter<redis.clients.jedis.Tuple, Tuple> TUPLE_LIST_TO_TUPLE_LIST_CONVERTER;
	private static final Converter<Object, RedisClusterNode> OBJECT_TO_CLUSTER_NODE_CONVERTER;
	private static final Converter<Expiration, byte[]> EXPIRATION_TO_COMMAND_OPTION_CONVERTER;
	private static final Converter<SetOption, byte[]> SET_OPTION_TO_COMMAND_OPTION_CONVERTER;

	public static final byte[] PLUS_BYTES;
	public static final byte[] MINUS_BYTES;
	public static final byte[] POSITIVE_INFINITY_BYTES;
	public static final byte[] NEGATIVE_INFINITY_BYTES;
	private static final byte[] EX;
	private static final byte[] PX;
	private static final byte[] NX;
	private static final byte[] XX;

	static {

		STRING_TO_BYTES = new Converter<String, byte[]>() {
			public byte[] convert(String source) {
				return source == null ? null : SafeEncoder.encode(source);
			}
		};
		STRING_LIST_TO_BYTE_LIST = new ListConverter<String, byte[]>(STRING_TO_BYTES);
		STRING_SET_TO_BYTE_SET = new SetConverter<String, byte[]>(STRING_TO_BYTES);
		STRING_MAP_TO_BYTE_MAP = new MapConverter<String, byte[]>(STRING_TO_BYTES);
		TUPLE_CONVERTER = new Converter<redis.clients.jedis.Tuple, Tuple>() {
			public Tuple convert(redis.clients.jedis.Tuple source) {
				return source != null ? new DefaultTuple(source.getBinaryElement(), source.getScore()) : null;
			}

		};
		TUPLE_SET_TO_TUPLE_SET = new SetConverter<redis.clients.jedis.Tuple, Tuple>(TUPLE_CONVERTER);
		TUPLE_LIST_TO_TUPLE_LIST_CONVERTER = new ListConverter<redis.clients.jedis.Tuple, Tuple>(TUPLE_CONVERTER);
		PLUS_BYTES = toBytes("+");
		MINUS_BYTES = toBytes("-");
		POSITIVE_INFINITY_BYTES = toBytes("+inf");
		NEGATIVE_INFINITY_BYTES = toBytes("-inf");

		OBJECT_TO_CLUSTER_NODE_CONVERTER = new Converter<Object, RedisClusterNode>() {

			@Override
			public RedisClusterNode convert(Object infos) {

				List<Object> values = (List<Object>) infos;
				RedisClusterNode.SlotRange range = new RedisClusterNode.SlotRange(((Number) values.get(0)).intValue(),
						((Number) values.get(1)).intValue());
				List<Object> nodeInfo = (List<Object>) values.get(2);
				return new RedisClusterNode(JedisConverters.toString((byte[]) nodeInfo.get(0)),
						((Number) nodeInfo.get(1)).intValue(), range);
			}
		};

		EX = toBytes("EX");
		PX = toBytes("PX");
		EXPIRATION_TO_COMMAND_OPTION_CONVERTER = new Converter<Expiration, byte[]>() {

			@Override
			public byte[] convert(Expiration source) {

				if (source == null || source.isPersistent()) {
					return new byte[0];
				}

				if (ObjectUtils.nullSafeEquals(TimeUnit.MILLISECONDS, source.getTimeUnit())) {
					return PX;
				}

				return EX;
			}
		};

		NX = toBytes("NX");
		XX = toBytes("XX");
		SET_OPTION_TO_COMMAND_OPTION_CONVERTER = new Converter<RedisStringCommands.SetOption, byte[]>() {

			@Override
			public byte[] convert(SetOption source) {

				switch (source) {
					case UPSERT:
						return new byte[0];
					case SET_IF_ABSENT:
						return NX;
					case SET_IF_PRESENT:
						return XX;
				}

				throw new IllegalArgumentException(String.format("Invalid argument %s for SetOption.", source));
			}

		};
	}

	public static Converter<String, byte[]> stringToBytes() {
		return STRING_TO_BYTES;
	}

	/**
	 * {@link ListConverter} converting jedis {@link redis.clients.jedis.Tuple} to {@link Tuple}.
	 * 
	 * @return
	 * @since 1.4
	 */
	public static ListConverter<redis.clients.jedis.Tuple, Tuple> tuplesToTuples() {
		return TUPLE_LIST_TO_TUPLE_LIST_CONVERTER;
	}

	public static ListConverter<String, byte[]> stringListToByteList() {
		return STRING_LIST_TO_BYTE_LIST;
	}

	public static SetConverter<String, byte[]> stringSetToByteSet() {
		return STRING_SET_TO_BYTE_SET;
	}

	public static MapConverter<String, byte[]> stringMapToByteMap() {
		return STRING_MAP_TO_BYTE_MAP;
	}

	public static SetConverter<redis.clients.jedis.Tuple, Tuple> tupleSetToTupleSet() {
		return TUPLE_SET_TO_TUPLE_SET;
	}

	public static Converter<Exception, DataAccessException> exceptionConverter() {
		return EXCEPTION_CONVERTER;
	}

	public static String[] toStrings(byte[][] source) {
		String[] result = new String[source.length];
		for (int i = 0; i < source.length; i++) {
			result[i] = SafeEncoder.encode(source[i]);
		}
		return result;
	}

	public static Set<Tuple> toTupleSet(Set<redis.clients.jedis.Tuple> source) {
		return TUPLE_SET_TO_TUPLE_SET.convert(source);
	}

	public static byte[] toBytes(Integer source) {
		return String.valueOf(source).getBytes();
	}

	public static byte[] toBytes(Long source) {
		return String.valueOf(source).getBytes();
	}

	/**
	 * @param source
	 * @return
	 * @since 1.6
	 */
	public static byte[] toBytes(Double source) {
		return toBytes(String.valueOf(source));
	}

	public static byte[] toBytes(String source) {
		return STRING_TO_BYTES.convert(source);
	}

	public static String toString(byte[] source) {
		return source == null ? null : SafeEncoder.encode(source);
	}

	/**
	 * @param source
	 * @return
	 * @since 1.7
	 */
	public static RedisClusterNode toNode(Object source) {
		return OBJECT_TO_CLUSTER_NODE_CONVERTER.convert(source);
	}

	/**
	 * @param source
	 * @return
	 * @since 1.3
	 */
	public static List<RedisClientInfo> toListOfRedisClientInformation(String source) {

		if (!StringUtils.hasText(source)) {
			return Collections.emptyList();
		}
		return STRING_TO_CLIENT_INFO_CONVERTER.convert(source.split("\\r?\\n"));
	}

	/**
	 * @param source
	 * @return
	 * @since 1.4
	 */
	public static List<RedisServer> toListOfRedisServer(List<Map<String, String>> source) {

		if (CollectionUtils.isEmpty(source)) {
			return Collections.emptyList();
		}

		List<RedisServer> sentinels = new ArrayList<RedisServer>();
		for (Map<String, String> info : source) {
			sentinels.add(RedisServer.newServerFrom(Converters.toProperties(info)));
		}
		return sentinels;
	}

	public static DataAccessException toDataAccessException(Exception ex) {
		return EXCEPTION_CONVERTER.convert(ex);
	}

	public static LIST_POSITION toListPosition(Position source) {
		Assert.notNull("list positions are mandatory");
		return (Position.AFTER.equals(source) ? LIST_POSITION.AFTER : LIST_POSITION.BEFORE);
	}

	public static byte[][] toByteArrays(Map<byte[], byte[]> source) {
		byte[][] result = new byte[source.size() * 2][];
		int index = 0;
		for (Map.Entry<byte[], byte[]> entry : source.entrySet()) {
			result[index++] = entry.getKey();
			result[index++] = entry.getValue();
		}
		return result;
	}

	public static SortingParams toSortingParams(SortParameters params) {
		SortingParams jedisParams = null;
		if (params != null) {
			jedisParams = new SortingParams();
			byte[] byPattern = params.getByPattern();
			if (byPattern != null) {
				jedisParams.by(params.getByPattern());
			}
			byte[][] getPattern = params.getGetPattern();
			if (getPattern != null) {
				jedisParams.get(getPattern);
			}
			Range limit = params.getLimit();
			if (limit != null) {
				jedisParams.limit((int) limit.getStart(), (int) limit.getCount());
			}
			Order order = params.getOrder();
			if (order != null && order.equals(Order.DESC)) {
				jedisParams.desc();
			}
			Boolean isAlpha = params.isAlphabetic();
			if (isAlpha != null && isAlpha) {
				jedisParams.alpha();
			}
		}
		return jedisParams;
	}

	public static BitOP toBitOp(BitOperation bitOp) {
		switch (bitOp) {
			case AND:
				return BitOP.AND;
			case OR:
				return BitOP.OR;
			case NOT:
				return BitOP.NOT;
			case XOR:
				return BitOP.XOR;
			default:
				throw new IllegalArgumentException();
		}
	}

	/**
	 * Converts a given {@link Boundary} to its binary representation suitable for {@literal ZRANGEBY*} commands, despite
	 * {@literal ZRANGEBYLEX}.
	 * 
	 * @param boundary
	 * @param defaultValue
	 * @return
	 * @since 1.6
	 */
	public static byte[] boundaryToBytesForZRange(Boundary boundary, byte[] defaultValue) {

		if (boundary == null || boundary.getValue() == null) {
			return defaultValue;
		}

		return boundaryToBytes(boundary, new byte[] {}, toBytes("("));
	}

	/**
	 * Converts a given {@link Boundary} to its binary representation suitable for ZRANGEBYLEX command.
	 * 
	 * @param boundary
	 * @return
	 * @since 1.6
	 */
	public static byte[] boundaryToBytesForZRangeByLex(Boundary boundary, byte[] defaultValue) {

		if (boundary == null || boundary.getValue() == null) {
			return defaultValue;
		}

		return boundaryToBytes(boundary, toBytes("["), toBytes("("));
	}

	/**
	 * Converts a given {@link Expiration} to the according {@code SET} command argument.<br />
	 * <dl>
	 * <dt>{@link TimeUnit#SECONDS}</dt>
	 * <dd>{@code EX}</dd>
	 * <dt>{@link TimeUnit#MILLISECONDS}</dt>
	 * <dd>{@code PX}</dd>
	 * </dl>
	 * 
	 * @param expiration
	 * @return
	 * @since 1.7
	 */
	public static byte[] toSetCommandExPxArgument(Expiration expiration) {
		return EXPIRATION_TO_COMMAND_OPTION_CONVERTER.convert(expiration);
	}

	/**
	 * Converts a given {@link SetOption} to the according {@code SET} command argument.<br />
	 * <dl>
	 * <dt>{@link SetOption#UPSERT}</dt>
	 * <dd>{@code byte[0]}</dd>
	 * <dt>{@link SetOption#SET_IF_ABSENT}</dt>
	 * <dd>{@code NX}</dd>
	 * <dt>{@link SetOption#SET_IF_PRESENT}</dt>
	 * <dd>{@code XX}</dd>
	 * </dl>
	 * 
	 * @param option
	 * @return
	 * @since 1.7
	 */
	public static byte[] toSetCommandNxXxArgument(SetOption option) {
		return SET_OPTION_TO_COMMAND_OPTION_CONVERTER.convert(option);
	}

	private static byte[] boundaryToBytes(Boundary boundary, byte[] inclPrefix, byte[] exclPrefix) {

		byte[] prefix = boundary.isIncluding() ? inclPrefix : exclPrefix;
		byte[] value = null;
		if (boundary.getValue() instanceof byte[]) {
			value = (byte[]) boundary.getValue();
		} else if (boundary.getValue() instanceof Double) {
			value = toBytes((Double) boundary.getValue());
		} else if (boundary.getValue() instanceof Long) {
			value = toBytes((Long) boundary.getValue());
		} else if (boundary.getValue() instanceof Integer) {
			value = toBytes((Integer) boundary.getValue());
		} else if (boundary.getValue() instanceof String) {
			value = toBytes((String) boundary.getValue());
		} else {
			throw new IllegalArgumentException(String.format("Cannot convert %s to binary format", boundary.getValue()));
		}

		ByteBuffer buffer = ByteBuffer.allocate(prefix.length + value.length);
		buffer.put(prefix);
		buffer.put(value);
		return buffer.array();

	}

	/**
	 * Convert {@link ScanOptions} to Jedis {@link ScanParams}.
	 *
	 * @param options
	 * @return
	 */
	public static ScanParams toScanParams(ScanOptions options) {
		
		ScanParams sp = new ScanParams();
		
		if (!options.equals(ScanOptions.NONE)) {
			if (options.getCount() != null) {
				sp.count(options.getCount().intValue());
			}
			if (StringUtils.hasText(options.getPattern())) {
				sp.match(options.getPattern());
			}
		}
		return sp;
	}

}
