package com.teagather.product.repository;

import com.teagather.product.entity.OptionGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OptionGroupRepository extends JpaRepository<OptionGroup, Long> {

    List<OptionGroup> findAllByOrderBySortOrderAscIdAsc();
}
