package com.expnai.relay.common.mapper;

public interface BaseMapper<E, D> {
	D toDto(E entity);
}
