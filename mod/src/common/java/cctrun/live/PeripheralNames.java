// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import java.util.Map;
import java.util.function.BiPredicate;

/** Multiple modem wrappers can expose the same physical peripheral. Only different targets are ambiguous. */
final class PeripheralNames {
    private PeripheralNames() {}
    static <T> void put(Map<String,T> map,String name,T value,BiPredicate<T,T> same) {
        var previous=map.putIfAbsent(name,value);
        if(previous!=null && previous!=value && !same.test(previous,value))throw new IllegalArgumentException("ambiguous peripheral name: "+name);
    }
}
