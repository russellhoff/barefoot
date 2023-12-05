package com.bmwcarit.barefoot.comparator;

import com.bmwcarit.barefoot.markov.Sample;

import java.util.Comparator;

public class SampleComparator implements Comparator<Sample> {
    @Override
    public int compare(Sample o1, Sample o2) {
        long i = o1.time() - o2.time();
        return (int) i;
    }
}
