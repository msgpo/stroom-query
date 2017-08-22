/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.common.v2.format;


import stroom.dashboard.expression.v1.TypeConverter;

public class Unformatted implements Formatter {
    private Unformatted() {
    }

    public static Unformatted create() {
        return new Unformatted();
    }

    @Override
    public String format(final Object value) {
        if (value == null) {
            return null;
        }

        final Double dbl = TypeConverter.getDouble(value);
        if (dbl != null) {
            if (Double.valueOf(dbl.longValue()).equals(dbl)) {
                return String.valueOf(dbl.longValue());
            } else {
                return dbl.toString();
            }
        }

        return value.toString();
    }
}