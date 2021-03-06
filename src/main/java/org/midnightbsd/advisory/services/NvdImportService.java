package org.midnightbsd.advisory.services;

import lombok.extern.slf4j.Slf4j;
import org.midnightbsd.advisory.model.Advisory;
import org.midnightbsd.advisory.model.ConfigNode;
import org.midnightbsd.advisory.model.ConfigNodeCpe;
import org.midnightbsd.advisory.model.Product;
import org.midnightbsd.advisory.model.Vendor;
import org.midnightbsd.advisory.model.nvd.Cve;
import org.midnightbsd.advisory.model.nvd.CveData;
import org.midnightbsd.advisory.model.nvd.CveItem;
import org.midnightbsd.advisory.model.nvd.DescriptionData;
import org.midnightbsd.advisory.model.nvd.Node;
import org.midnightbsd.advisory.model.nvd.NodeCpe;
import org.midnightbsd.advisory.model.nvd.ProblemTypeData;
import org.midnightbsd.advisory.model.nvd.ProblemTypeDataDescription;
import org.midnightbsd.advisory.model.nvd.ProductData;
import org.midnightbsd.advisory.model.nvd.VendorData;
import org.midnightbsd.advisory.model.nvd.VersionData;
import org.midnightbsd.advisory.repository.ConfigNodeCpeRepository;
import org.midnightbsd.advisory.repository.ConfigNodeRepository;
import org.midnightbsd.advisory.repository.ProductRepository;
import org.midnightbsd.advisory.repository.VendorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * @author Lucas Holt
 */
@Slf4j
@Service
public class NvdImportService {

    @Autowired
    private AdvisoryService advisoryService;

    @Autowired
    private VendorRepository vendorRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ConfigNodeRepository configNodeRepository;

    @Autowired
    private ConfigNodeCpeRepository configNodeCpeRepository;

    private String getProblemType(final Cve cve) {
        final StringBuilder sb = new StringBuilder();
        for (final ProblemTypeData ptd : cve.getProblemType().getProblemTypeData()) {
            for (final ProblemTypeDataDescription dd : ptd.getDescription()) {
                sb.append(dd.getValue()).append(",");
            }
        }
        return sb.toString();
    }

    private Vendor createOrFetchVendor(final VendorData vendorData) {
        Vendor v = vendorRepository.findOneByName(vendorData.getVendorName());
        if (v == null) {
            v = new Vendor();
            v.setName(vendorData.getVendorName());
            v = vendorRepository.saveAndFlush(v);
        }
        return v;
    }

    private Product createOrFetchProduct(final ProductData pd, final VersionData vd, final Vendor v) {
        Product product = productRepository.findByNameAndVersionAndVendor(pd.getProductName(), vd.getVersionValue(), v);
        if (product == null) {
            product = new Product();
            product.setName(pd.getProductName());
            product.setVersion(vd.getVersionValue());
            product.setVendor(v);
            product = productRepository.saveAndFlush(product);
        }
        return product;
    }

    private Set<Product> processVendorAndProducts(final Cve cve) {
        final Set<Product> advProducts = new HashSet<>();
        if (cve.getAffects() == null || cve.getAffects().getVendor() == null)
            return advProducts;

        log.info("Vendor count: {}", cve.getAffects().getVendor().getVendorData().size());

        for (final VendorData vendorData : cve.getAffects().getVendor().getVendorData()) {
            final Vendor v = createOrFetchVendor(vendorData);

            log.info("Product count {}", vendorData.getProduct().getProductData().size());
            for (final ProductData pd : vendorData.getProduct().getProductData()) {
                for (final VersionData vd : pd.getVersion().getVersionData()) {
                    advProducts.add(createOrFetchProduct(pd, vd, v));
                }
            }
        }

        return advProducts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void importNvd(final CveData cveData) {
        if (cveData == null)
            throw new IllegalArgumentException("cveData");

        if (cveData.getCveItems() == null || cveData.getCveItems().isEmpty())
            throw new IllegalArgumentException("cveData.getItems()");

        for (final CveItem cveItem : cveData.getCveItems()) {
            final Cve cve = cveItem.getCve();
            Advisory advisory = new Advisory();

            if (cve.getCveDataMeta() == null) {
                log.warn("invalid metadata");
                continue;
            }

            advisory.setCveId(cve.getCveDataMeta().getID());
            log.info("Processing {}", advisory.getCveId());

            if (cve.getProblemType() != null && cve.getProblemType().getProblemTypeData() != null)  {
                advisory.setProblemType(getProblemType(cve));
            }

            advisory.setPublishedDate(convertDate(cveItem.getPublishedDate()));
            advisory.setLastModifiedDate(convertDate(cveItem.getLastModifiedDate()));

            if (cve.getDescription() != null && cve.getDescription().getDescriptionData() != null) {
                for(final DescriptionData descriptionData : cve.getDescription().getDescriptionData()) {
                   if (descriptionData.getLang().equalsIgnoreCase("en"))
                        advisory.setDescription(descriptionData.getValue());
                }
            }

            // determine severity
            if (cveItem.getImpact() != null && cveItem.getImpact().getBaseMetricV2() != null) {
                advisory.setSeverity(cveItem.getImpact().getBaseMetricV2().getSeverity());
            }
            
            advisory.setProducts(processVendorAndProducts(cve));
            advisory = advisoryService.save(advisory);

            // now save configurations
            if (cveItem.getConfigurations() != null && cveItem.getConfigurations().getNodes() != null) {
                log.info("Now save configurations for {}", advisory.getCveId());
                for (final Node node : cveItem.getConfigurations().getNodes()) {
                     if (node.getOperator() != null) {
                         ConfigNode configNode = new ConfigNode();
                         configNode.setAdvisory(advisory);
                         configNode.setOperator(node.getOperator());

                         configNode = configNodeRepository.save(configNode); // save top level item

                         if (node.getCpe() != null) {
                             for (final NodeCpe nodeCpe : node.getCpe()) {
                                 final ConfigNodeCpe cpe = new ConfigNodeCpe();

                                 cpe.setCpe22Uri(nodeCpe.getCpe22Uri());
                                 cpe.setCpe23Uri(nodeCpe.getCpe23Uri());
                                 cpe.setVulnerable(nodeCpe.getVulnerable());
                                 cpe.setConfigNode(configNode);

                                 configNodeCpeRepository.save(cpe);
                             }
                         }

                         if (node.getChildren() != null) {
                             for (final Node childNode : node.getChildren()) {
                                 if (childNode.getOperator() != null) {
                                     final ConfigNode cn = new ConfigNode();
                                     cn.setAdvisory(advisory);
                                     cn.setOperator(node.getOperator());
                                     cn.setParentId(configNode.getId());
                                     configNodeRepository.save(cn);

                                     if (childNode.getCpe() != null) {
                                         for (final NodeCpe nodeCpe : childNode.getCpe()) {
                                             final ConfigNodeCpe cpe = new ConfigNodeCpe();

                                             cpe.setCpe22Uri(nodeCpe.getCpe22Uri());
                                             cpe.setCpe23Uri(nodeCpe.getCpe23Uri());
                                             cpe.setVulnerable(nodeCpe.getVulnerable());
                                             cpe.setConfigNode(cn);

                                             configNodeCpeRepository.save(cpe);
                                         }
                                     }

                                    // currently do not support child child nodes.
                                 }
                             }
                         }

                     }
                }

                configNodeRepository.flush();
                configNodeCpeRepository.flush();
            }
        }
    }

    private Date convertDate(final String dt) {
        if (dt == null || dt.isEmpty())
            return null;

        // 2018-02-20T21:29Z

        try {
            // deepcode ignore FixDateFormat: API we're calling doesn't output in JS date format
            final SimpleDateFormat iso8601Dateformat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            return iso8601Dateformat.parse(dt);
        } catch (final Exception e) {
            log.error("Could not convert date string {}", dt, e);
        }

        return null;
    }
}
