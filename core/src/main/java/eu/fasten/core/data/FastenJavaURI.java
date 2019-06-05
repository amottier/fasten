/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.fasten.core.data;

import java.net.URI;

import it.unimi.dsi.fastutil.chars.CharOpenHashSet;

/** A class representing a Fasten URI for the Java language; it has to be considered experimental until the BNF for such URIs is set in stone. */

public class FastenJavaURI extends FastenURI {
	private final static FastenJavaURI[] NO_ARGS_ARRAY = new FastenJavaURI[0];
	protected final String className;
	protected final String functionOrAttributeName;
	protected final FastenJavaURI[] args;
	protected final FastenJavaURI returnType;

	public FastenJavaURI(final String s) {
		this(URI.create(s));
	}

	public FastenJavaURI(final URI uri) {
		super(uri);
		if (rawEntity == null) {
			className = null;
			functionOrAttributeName = null;
			returnType = null;
			args = null;
			return;
		}
		final var dotPos = rawEntity.indexOf(".");
		if (dotPos == -1) { // entity-type
			className = decode(rawEntity);
			functionOrAttributeName = null;
			returnType = null;
			args = null;
			return;
		}
		className = decode(rawEntity.substring(0, dotPos));
		final var funcArgsType = decode(rawEntity.substring(dotPos + 1));
		final var openParenPos = funcArgsType.indexOf('(');
		if (openParenPos == -1) { // entity-attribute
			args = null;
			returnType = null;
			functionOrAttributeName = null;
			return;
		}
		functionOrAttributeName = decode(funcArgsType.substring(0, openParenPos));
		final var closedParenPos = funcArgsType.indexOf(')');
		if (closedParenPos == -1) throw new IllegalArgumentException("Missing close parenthesis");
		returnType = FastenJavaURI.create(decode(funcArgsType.substring(closedParenPos + 1)));
		final var argString = funcArgsType.substring(openParenPos + 1, closedParenPos);
		if (argString.length() == 0) {
			args = NO_ARGS_ARRAY;
			return;
		}

		final var a = argString.split(",");
		args = new FastenJavaURI[a.length];
		for(int i = 0; i < a.length; i++) args[i] = FastenJavaURI.create(decode(a[i]));
	}

	/**
	 * Creates a {@link FastenURI} from a string, with the same logic of {@link URI#create(String)}.
	 * @param s a string specifying a {@link FastenURI}.
	 * @return a {@link FastenURI}.
	 */

	public static FastenJavaURI create(final String s) {
		return new FastenJavaURI(URI.create(s));
	}

	/**
	 * Creates a {@link FastenJavaURI} from a {@link URI}.
	 * @param uri a {@link URI} a specifying a {@link FastenJavaURI}.
	 * @return a {@link FastenJavaURI}.
	 * @throws IllegalArgumentException if the argument does not satisfy the further constraints of a {@link FastenJavaURI}.
	 */

	public static FastenJavaURI create(final URI uri) {
		return new FastenJavaURI(uri);
	}

	public static FastenJavaURI create(final String rawForge, final String rawProduct, final String rawVersion, final String rawNamespace, final String className, final String functionOrAttributeName, final FastenJavaURI[] relativizedArgs, final FastenJavaURI relativizedReturnType) {
		// TODO: percent uppercasing
		final StringBuffer entitysb = new StringBuffer();
		entitysb.append(className + ".");
		entitysb.append(functionOrAttributeName + "(");
		for (int i = 0; i < relativizedArgs.length; i++) {
			if (i>0) entitysb.append(",");
			entitysb.append(FastenJavaURI.pctEncodeArg(relativizedArgs[i].uri.toString()));
		}
		entitysb.append(")");
		entitysb.append(FastenJavaURI.pctEncodeArg(relativizedReturnType.uri.toString()));
		final FastenURI fastenURI = FastenURI.create(rawForge, rawProduct, rawVersion, rawNamespace, entitysb.toString());
		return create(fastenURI.uri);
	}

	private final static CharOpenHashSet typeChar = new CharOpenHashSet(new char[] {
				'-', '.', '_', '~', // unreserved
				'!', '$', '&', '\'', '*', ';', '=', // sub-delims-type
				'@'
			},
			.5f);

	public static String pctEncodeArg(final String s) {
		// Encoding characters not in arg-char (see BNF)
		final StringBuffer sb = new StringBuffer();
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (c < 0xFF && !Character.isLetterOrDigit(c) && ! typeChar.contains(c))
				sb.append("%" + String.format("%02X", Integer.valueOf(c)));
			else
				sb.append(c);
		}
		return sb.toString();
	}

	public String getClassName() {
		return className;
	}

	public String getFunctionName() {
		return functionOrAttributeName;
	}

	public FastenJavaURI[] getArgs() {
		return args.clone(); // defensive copy?
	}

	public FastenJavaURI getReturnType() {
		return returnType;
	}

	@Override
	public FastenJavaURI relativize(final FastenURI u) {
		if (rawNamespace == null) throw new IllegalStateException("You cannot relativize without a namespace");
		final String rawAuthority = u.uri.getRawAuthority();
		// There is an authority and it doesn't match: return u
		if (rawAuthority != null && ! rawAuthority.equals(uri.getRawAuthority())) return u instanceof FastenJavaURI ? (FastenJavaURI) u : create(u.uri);
		// Matching authorities, or no authority, and there's a namespace, and it doesn't match: return namespace + entity
		if (u.rawNamespace != null && ! rawNamespace.equals(u.rawNamespace)) return FastenJavaURI.create("/" + u.rawNamespace + "/" +  u.rawEntity);
		// Matching authorities, or no authority, matching namespaces, or no namespace: return entity
		return FastenJavaURI.create(u.getRawEntity());
	}

	public FastenJavaURI resolve(final FastenJavaURI u) {
		// Standard resolution will work; might be more efficient
		return create(this.uri.resolve(u.uri));
	}

	@Override
	public FastenJavaURI canonicalize() {
		final FastenJavaURI[] relativizedArgs = new FastenJavaURI[args.length];

		for(int i = 0; i < args.length; i++) relativizedArgs[i] = relativize(args[i]);
		final FastenJavaURI relativizedReturnType = relativize(returnType);
		return FastenJavaURI.create(rawForge, rawProduct, rawVersion, rawNamespace, className, functionOrAttributeName, relativizedArgs, relativizedReturnType);
	}
}
