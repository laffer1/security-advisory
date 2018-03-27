package org.midnightbsd.advisory.repository;

import org.midnightbsd.advisory.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author Lucas Holt
 */
@Repository
public interface ProductRepository extends JpaRepository<Product, Integer> {
    List<Product> findAllByOrderByVersionAsc();

    List<Product> findByName(@Param("name") String name);

    Product findByNameAndVersion(@Param("name") String name, @Param("version") String version);
}
