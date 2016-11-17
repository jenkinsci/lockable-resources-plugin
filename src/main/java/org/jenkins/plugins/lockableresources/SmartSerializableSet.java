/*
 * The MIT License
 *
 * Copyright 2016 Eb.
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

import com.google.common.collect.Lists;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.collections.CollectionConverter;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import com.thoughtworks.xstream.mapper.Mapper;
import hudson.model.Items;
import hudson.model.Run;
import hudson.model.UpdateCenter;
import hudson.model.User;
import hudson.util.XStream2;
import java.io.Serializable;
import java.util.LinkedHashSet;
import jenkins.model.Jenkins;

/**
 * A standard ArrayList, but that will serialize only serializable items<br>
 * Non serializable items are kept in the list but will not survive a deserialization
 *
 * @author Eb
 * @param <T>
 */
public class SmartSerializableSet<T> extends LinkedHashSet<T> {
    private static final MyConverter CONVERTER = new MyConverter(Jenkins.XSTREAM2.getMapper());
    private static final long serialVersionUID = 1L;

    static {
        for(XStream2 xstream2 : Lists.newArrayList(Jenkins.XSTREAM2, Run.XSTREAM2, UpdateCenter.XSTREAM, User.XSTREAM, Items.XSTREAM2)) {
            xstream2.registerConverter(CONVERTER);
        }
    }

    private static class MyConverter extends CollectionConverter {
        private MyConverter(Mapper mapper) {
            super(mapper);
        }

        @Override
        public boolean canConvert(Class type) {
            return type.equals(SmartSerializableSet.class);
        }

        @Override
        protected void writeItem(Object item, MarshallingContext context, HierarchicalStreamWriter writer) {
            if(item instanceof Serializable) {
                super.writeItem(item, context, writer);
            }
        }
    }
}
