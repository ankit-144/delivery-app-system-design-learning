package dev.quickdrop.core.rider;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderRepository extends JpaRepository<Rider, Long> {

    List<Rider> findByCityId(short cityId);
}
