/*
 * The MIT License
 *
 * Copyright (C) 2015 Freescale Semiconductor, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources;

import java.util.Arrays;
import java.util.HashSet;


/**
 * Extension of class HashSet with overridden toString method in order to easily create a set out of
 * a string containing tokens separated by whitespace. This is used mainly for reservedForNodes and labelSets.
 * The toString method will also return a string of tokens separated by whitespace; if the set is
 * empty it will return null instead.
 */
public class WhitespaceSet extends HashSet<String> {

	public WhitespaceSet(String str) {
		super(Arrays.asList(str.split("\\s+")));
		/* remove empty element, if it exists */
		this.remove("");
	}

	@Override
	public String toString() {
		if(this.isEmpty())
			return null;

		String result = "";
		for(String s : this)
			result += s + " ";

		return result.trim();
	}
}
