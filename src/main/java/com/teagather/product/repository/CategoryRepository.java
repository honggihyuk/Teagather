package com.teagather.product.repository;

import com.teagather.product.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortOrderAscIdAsc();

    Optional<Category> findByName(String name);
}
