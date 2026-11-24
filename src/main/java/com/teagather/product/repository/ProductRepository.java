package com.teagather.product.repository;

import com.teagather.product.constant.ProductStatus;
import com.teagather.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("select p from Product p left join fetch p.category c order by c.sortOrder asc, p.sortOrder asc, p.id asc")
    List<Product> findAllOrdered();

    @Query("select p from Product p left join fetch p.category c where p.status in :statuses order by c.sortOrder asc, p.sortOrder asc, p.id asc")
    List<Product> findByStatusIn(Collection<ProductStatus> statuses);

    List<Product> findByTodayMenuTrue();

    long countByCategoryId(Long categoryId);
}
