package com.dietguide.domain;

/** 版本链上的条目：revision 单调递增，mode 决定是否重置有效集合。 */
public interface Versioned {

    int revision();

    String mode();
}
