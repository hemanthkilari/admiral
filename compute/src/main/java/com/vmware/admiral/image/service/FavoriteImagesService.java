/*
 * Copyright (c) 2018 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.image.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Level;

import com.google.gson.JsonSyntaxException;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.FileUtil;
import com.vmware.admiral.service.common.MultiTenantDocument;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public class FavoriteImagesService extends StatefulService {
    public static final String FACTORY_LINK = ManagementUriParts.FAVORITE_IMAGES;
    private static final String POPULAR_IMAGES_FILE = "/popular-images.json";

    public FavoriteImagesService() {
        super(FavoriteImage.class);
        super.toggleOption(ServiceOption.PERSISTENCE, true);
        super.toggleOption(ServiceOption.REPLICATION, true);
        super.toggleOption(ServiceOption.OWNER_SELECTION, true);
        super.toggleOption(ServiceOption.INSTRUMENTATION, true);
        super.toggleOption(ServiceOption.IDEMPOTENT_POST, true);
    }

    public static List<FavoriteImage> buildDefaultFavoriteImages(ServiceHost host) {
        List<FavoriteImage> images = new ArrayList<>();
        try {
            List jsonImages = Utils.fromJson(FileUtil.getClasspathResourceAsString(POPULAR_IMAGES_FILE), List.class);

            host.log(Level.INFO, "Default favorite images loaded.");

            Function<FavoriteImage, String> createSelfLink = state -> {
                return UriUtils.buildUriPath(FavoriteImagesService.FACTORY_LINK,
                        new StringBuilder().append(state.registry.replaceFirst("https?://", "")
                        .replaceAll("\\.", "-"))
                                .append('-')
                                .append(state.name.replaceAll("/", "-")
                                .replaceAll("\\.", "-"))
                                .toString());
            };

            jsonImages.forEach(i -> {
                Map<String, String> imgObj = (Map<String, String>) i;
                FavoriteImage state = new FavoriteImage();
                state.name = imgObj.get(FavoriteImage.FIELD_NAME_NAME);
                state.description = imgObj.get(FavoriteImage.FIELD_NAME_DESCRIPTION);
                state.registry = imgObj.get(FavoriteImage.FIELD_NAME_REGISTRY);
                state.documentSelfLink = createSelfLink.apply(state);
                images.add(state);
            });
        } catch (NullPointerException | JsonSyntaxException e) {
            host.log(Level.WARNING, "Unable to load default favorite images. " +
                    "Either the file is missing or it is malformed");
        }
        return images;
    }

    public static class FavoriteImage extends MultiTenantDocument {

        public static final String FIELD_NAME_NAME = "name";
        public static final String FIELD_NAME_DESCRIPTION = "description";
        public static final String FIELD_NAME_REGISTRY = "registry";

        public String name;
        public String description;
        public String registry;

        @Override
        public int hashCode() {
            return Objects.hash(name, description, registry);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof FavoriteImage)) {
                return false;
            }
            FavoriteImage i = (FavoriteImage) obj;
            boolean tenantLinkClause = (i.tenantLinks != null ? i.tenantLinks : Collections.emptyList()).equals(
                    (this.tenantLinks != null ? this.tenantLinks : Collections.emptyList()));

            return i.name.equals(this.name) &&
                    i.registry.equals(this.registry) &&
                    tenantLinkClause;
        }
    }
}