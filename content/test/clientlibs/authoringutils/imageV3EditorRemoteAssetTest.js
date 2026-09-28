/*******************************************************************************
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
/**
 * Image v3 editor remote asset (urn:aaid:aem) Dynamic Media dialog behaviour.
 * Depends on {@code imageV3EditorImageTest.js} / {@code image.js} loading first.
 */
function imageV3EditorRemoteAssetIsParentVisible(element) {
    return element?.parentElement?.style.display !== "none";
}

function imageV3EditorRemoteAssetIsGroupVisible(root) {
    const group = root.querySelector(".cmp-image__editor-dynamicmedia");
    return group?.style.display !== "none";
}

function imageV3EditorRemoteAssetCreateDynamicMediaDialogFixture() {
    const root = document.createElement("div");
    root.className = "cmp-image__editor";

    const pageImageCheckbox = document.createElement("coral-checkbox");
    pageImageCheckbox.setAttribute("name", "./imageFromPageImage");
    pageImageCheckbox.checked = false;
    root.appendChild(pageImageCheckbox);

    const group = document.createElement("div");
    group.className = "cmp-image__editor-dynamicmedia";
    group.style.display = "none";
    root.appendChild(group);

    const presetTypeWrapper = document.createElement("div");
    const presetType = document.createElement("div");
    presetType.className = "cmp-image__editor-dynamicmedia-presettype";
    const imagePresetRadio = document.createElement("input");
    imagePresetRadio.type = "radio";
    imagePresetRadio.name = "./dmPresetType";
    imagePresetRadio.value = "imagePreset";
    imagePresetRadio.checked = false;
    const smartCropRadio = document.createElement("input");
    smartCropRadio.type = "radio";
    smartCropRadio.name = "./dmPresetType";
    smartCropRadio.value = "smartCrop";
    presetType.appendChild(imagePresetRadio);
    presetType.appendChild(smartCropRadio);
    presetTypeWrapper.appendChild(presetType);
    group.appendChild(presetTypeWrapper);

    const imagePresetWrapper = document.createElement("div");
    const imagePreset = document.createElement("select");
    imagePreset.className = "cmp-image__editor-dynamicmedia-imagepreset";
    imagePresetWrapper.appendChild(imagePreset);
    group.appendChild(imagePresetWrapper);

    const smartCropWrapper = document.createElement("div");
    const smartCrop = document.createElement("coral-select");
    smartCrop.className = "cmp-image__editor-dynamicmedia-smartcroprendition";
    smartCropWrapper.appendChild(smartCrop);
    group.appendChild(smartCropWrapper);

    const modifiersWrapper = document.createElement("div");
    const modifiers = document.createElement("input");
    modifiers.setAttribute("name", "./imageModifiers");
    modifiersWrapper.appendChild(modifiers);
    group.appendChild(modifiersWrapper);

    return root;
}

function imageV3EditorRemoteAssetCreatePolarisMetadataXhrSpy() {
    const originalXhr = globalThis.XMLHttpRequest;
    let polarisMetadataRequested = false;
    let polarisMetadataUrl = null;
    globalThis.XMLHttpRequest = function() {
        const xhr = new originalXhr();
        const originalOpen = xhr.open;
        xhr.open = function(method, url) {
            if (typeof url === "string" && url.indexOf("/adobe/assets") !== -1 && url.indexOf("/metadata") !== -1) {
                polarisMetadataRequested = true;
                polarisMetadataUrl = url;
            }
            return originalOpen.apply(xhr, arguments);
        };
        return xhr;
    };
    return {
        wasPolarisMetadataRequested: function() {
            return polarisMetadataRequested;
        },
        getPolarisMetadataUrl: function() {
            return polarisMetadataUrl;
        },
        restore: function() {
            globalThis.XMLHttpRequest = originalXhr;
        }
    };
}

/**
 * Minimal stand-in for the {@code $fileUpload} jQuery wrapper used by {@code resolveVanityAssetId}
 * ({@code data}, {@code find}, {@code get}), backed by a real DOM element holding the {@code ./fileReference} input.
 */
function imageV3EditorRemoteAssetCreateFileUploadStub(fileReference) {
    const element = document.createElement("div");
    const input = document.createElement("input");
    input.setAttribute("name", "./fileReference");
    input.value = fileReference;
    element.appendChild(input);
    document.body.appendChild(element);
    const store = {};
    const stub = {
        data: function(key, value) {
            if (value === undefined) {
                return store[key];
            }
            store[key] = value;
            return stub;
        },
        find: function(selector) {
            return globalThis.jQuery(element).find(selector);
        },
        get: function() {
            return element;
        },
        getFileReference: function() {
            return input.value;
        },
        remove: function() {
            element.remove();
        }
    };
    return stub;
}

describe("Image v3 editor remote asset Dynamic Media", function() {
    let api;
    let fixtureRoot;
    const isParentVisible = imageV3EditorRemoteAssetIsParentVisible;
    const isGroupVisible = imageV3EditorRemoteAssetIsGroupVisible;
    const createDynamicMediaDialogFixture = imageV3EditorRemoteAssetCreateDynamicMediaDialogFixture;
    const remoteFileReference = "/urn:aaid:aem:abc-123/landscape.jpg";

    beforeAll(function() {
        api = globalThis.__IMAGE_V3_EDITOR_TEST_API;
    });

    beforeEach(function() {
        fixtureRoot = createDynamicMediaDialogFixture();
        document.body.appendChild(fixtureRoot);
        api.installRemoteAssetDynamicMediaTestFixture(fixtureRoot);
    });

    afterEach(function() {
        fixtureRoot?.remove();
        fixtureRoot = null;
    });

    describe("isRemoteFileReference", function() {
        it("returns true for the canonical /urn:aaid:aem:<assetID>/seoname.format reference", function() {
            expect(api.isRemoteFileReference("/urn:aaid:aem:abc-123/landscape.jpg")).toBe(true);
        });

        it("returns true for a vanity /urn:avid:aem:<vanityID>/seoname.format reference", function() {
            expect(api.isRemoteFileReference("/urn:avid:aem:VanityCheck/check.jpeg")).toBe(true);
        });

        it("returns false for DAM paths and empty values", function() {
            expect(api.isRemoteFileReference("/content/dam/sample.jpg")).toBe(false);
            expect(api.isRemoteFileReference("")).toBe(false);
            expect(api.isRemoteFileReference(null)).toBe(false);
            expect(api.isRemoteFileReference(undefined)).toBe(false);
        });

        it("returns false when urn:aaid:aem is not at the path root", function() {
            expect(api.isRemoteFileReference("urn:aaid:aem:abc-123/landscape.jpg")).toBe(false);
            expect(api.isRemoteFileReference("/content/dam/urn:aaid:aem/asset")).toBe(false);
        });

        it("returns false when urn:avid:aem is not at the path root", function() {
            expect(api.isRemoteFileReference("urn:avid:aem:VanityCheck/check.jpeg")).toBe(false);
            expect(api.isRemoteFileReference("/content/dam/urn:avid:aem/asset")).toBe(false);
        });
    });

    describe("processPolarisSmartCropMetadataResponse", function() {
        it("shows preset type, smart crop radio, smart crop dropdown, and image modifiers when smartcrops metadata is present", function() {
            const responseText = JSON.stringify({
                repositoryMetadata: {
                    smartcrops: {
                        Landscape: { width: 16, height: 9 }
                    }
                }
            });

            api.processPolarisSmartCropMetadataResponse(200, responseText);

            const smartCrop = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-smartcroprendition");
            const modifiers = fixtureRoot.querySelector("input[name='./imageModifiers']");
            const presetType = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-presettype");
            const smartCropRadio = fixtureRoot.querySelector(
                ".cmp-image__editor-dynamicmedia-presettype input[value='smartCrop']"
            );
            expect(isGroupVisible(fixtureRoot)).toBe(true);
            expect(isParentVisible(presetType)).toBe(true);
            expect(smartCropRadio.checked).toBe(true);
            expect(isParentVisible(smartCrop)).toBe(true);
            expect(isParentVisible(modifiers)).toBe(true);
        });

        it("re-shows preset type after a previous no-smartcrops response had hidden it", function() {
            const noSmartCrops = JSON.stringify({
                repositoryMetadata: {}
            });
            const withSmartCrops = JSON.stringify({
                repositoryMetadata: {
                    smartcrops: {
                        Landscape: { width: 16, height: 9 }
                    }
                }
            });

            api.processPolarisSmartCropMetadataResponse(200, noSmartCrops);
            api.processPolarisSmartCropMetadataResponse(200, withSmartCrops);

            const presetType = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-presettype");
            expect(isParentVisible(presetType)).toBe(true);
        });

        it("shows image modifiers but hides smart crop, image preset, and preset type when smartcrops metadata is absent", function() {
            const responseText = JSON.stringify({
                repositoryMetadata: {}
            });

            api.processPolarisSmartCropMetadataResponse(200, responseText);

            const smartCrop = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-smartcroprendition");
            const modifiers = fixtureRoot.querySelector("input[name='./imageModifiers']");
            const presetType = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-presettype");
            const imagePreset = fixtureRoot.querySelector(".cmp-image__editor-dynamicmedia-imagepreset");
            expect(isGroupVisible(fixtureRoot)).toBe(true);
            expect(isParentVisible(modifiers)).toBe(true);
            expect(isParentVisible(smartCrop)).toBe(false);
            expect(isParentVisible(presetType)).toBe(false);
            expect(isParentVisible(imagePreset)).toBe(false);
        });

        it("keeps original preset behaviour by hiding image preset radio parent", function() {
            const responseText = JSON.stringify({
                repositoryMetadata: {
                    smartcrops: {
                        Square: {}
                    }
                }
            });

            api.processPolarisSmartCropMetadataResponse(200, responseText);

            const imagePresetRadio = fixtureRoot.querySelector(
                ".cmp-image__editor-dynamicmedia-presettype input[value='imagePreset']"
            );
            expect(isParentVisible(imagePresetRadio)).toBe(false);
        });

        it("does nothing when metadata request fails", function() {
            api.processPolarisSmartCropMetadataResponse(500, "{}");

            expect(isGroupVisible(fixtureRoot)).toBe(false);
        });
    });

    describe("retrieveDAMInfo", function() {
        afterEach(function() {
            api.setRetrieveDAMTestState({
                isPolarisEnabled: false,
                areDMFeaturesEnabled: false,
                polarisRepositoryId: ""
            });
            delete globalThis.jQuery._ajaxHandler;
        });

        it("does not request Polaris metadata when DM features are disabled for a remote asset", function(done) {
            const xhrSpy = imageV3EditorRemoteAssetCreatePolarisMetadataXhrSpy();
            let ajaxUrl;
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                ajaxUrl = options.url;
                resolve({});
            };
            api.setRetrieveDAMTestState({
                isPolarisEnabled: true,
                areDMFeaturesEnabled: false,
                polarisRepositoryId: "repo.test"
            });

            api.retrieveDAMInfo(remoteFileReference);
            setTimeout(function() {
                expect(xhrSpy.wasPolarisMetadataRequested()).toBe(false);
                expect(ajaxUrl).toBe(remoteFileReference + "/_jcr_content/metadata.json");
                xhrSpy.restore();
                done();
            }, 10);
        });

        it("requests Polaris metadata when DM features are enabled for a remote asset", function(done) {
            const xhrSpy = imageV3EditorRemoteAssetCreatePolarisMetadataXhrSpy();
            api.setRetrieveDAMTestState({
                isPolarisEnabled: true,
                areDMFeaturesEnabled: true,
                polarisRepositoryId: "repo.test"
            });

            api.retrieveDAMInfo(remoteFileReference).then(function() {
                expect(xhrSpy.wasPolarisMetadataRequested()).toBe(true);
                xhrSpy.restore();
                done();
            });
        });

        it("requests Polaris metadata by vanity id for a urn:avid:aem remote asset", function(done) {
            const xhrSpy = imageV3EditorRemoteAssetCreatePolarisMetadataXhrSpy();
            api.setRetrieveDAMTestState({
                isPolarisEnabled: true,
                areDMFeaturesEnabled: true,
                polarisRepositoryId: "repo.test"
            });

            api.retrieveDAMInfo("/urn:avid:aem:VanityCheck/check.jpeg").then(function() {
                expect(xhrSpy.getPolarisMetadataUrl()).toBe("https://repo.test/adobe/assets/urn:avid:aem:VanityCheck/metadata");
                xhrSpy.restore();
                done();
            });
        });
    });

    describe("resolveVanityAssetId", function() {
        const vanityResolverPath = "/bin/wcm/core/components/image/v3/vanityid";
        let fileUpload;
        let originalContains;

        beforeEach(function() {
            originalContains = globalThis.jQuery.contains;
            globalThis.jQuery.contains = function(container, contained) {
                return container !== contained && container.contains(contained);
            };
            api.setVanityIdTestState({ vanityIdProperty: "dc:description" });
        });

        afterEach(function() {
            api.setVanityIdTestState({ vanityIdProperty: "" });
            delete globalThis.jQuery._ajaxHandler;
            if (originalContains === undefined) {
                delete globalThis.jQuery.contains;
            } else {
                globalThis.jQuery.contains = originalContains;
            }
            fileUpload?.remove();
            fileUpload = null;
        });

        it("rewrites fileReference to the vanity reference when a vanityId is returned", function(done) {
            fileUpload = imageV3EditorRemoteAssetCreateFileUploadStub(remoteFileReference);
            let ajaxOptions;
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                ajaxOptions = options;
                resolve({ vanityId: "VanityCheck" });
            };

            api.resolveVanityAssetId(remoteFileReference, fileUpload);
            setTimeout(function() {
                expect(ajaxOptions.url).toBe(vanityResolverPath);
                expect(ajaxOptions.data).toEqual({ assetId: "urn:aaid:aem:abc-123", property: "dc:description" });
                expect(fileUpload.getFileReference()).toBe("/urn:avid:aem:VanityCheck/landscape.jpg");
                done();
            }, 10);
        });

        it("keeps the original fileReference when the response is empty", function(done) {
            fileUpload = imageV3EditorRemoteAssetCreateFileUploadStub(remoteFileReference);
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                resolve("");
            };

            api.resolveVanityAssetId(remoteFileReference, fileUpload);
            setTimeout(function() {
                expect(fileUpload.getFileReference()).toBe(remoteFileReference);
                done();
            }, 10);
        });

        it("ignores a stale response when a newer selection was made", function(done) {
            const secondReference = "/urn:aaid:aem:def-456/portrait.jpg";
            fileUpload = imageV3EditorRemoteAssetCreateFileUploadStub(remoteFileReference);
            const pending = [];
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                pending.push({ options: options, resolve: resolve });
            };

            api.resolveVanityAssetId(remoteFileReference, fileUpload);
            fileUpload.find("input[name='./fileReference']").val(secondReference);
            api.resolveVanityAssetId(secondReference, fileUpload);
            setTimeout(function() {
                expect(pending.length).toBe(2);
                pending[1].resolve({ vanityId: "Fresh" });
                pending[0].resolve({ vanityId: "Stale" });
                expect(fileUpload.getFileReference()).toBe("/urn:avid:aem:Fresh/portrait.jpg");
                done();
            }, 10);
        });

        it("does not resolve references that already use a vanity id", function(done) {
            const vanityReference = "/urn:avid:aem:VanityCheck/check.jpeg";
            fileUpload = imageV3EditorRemoteAssetCreateFileUploadStub(vanityReference);
            let ajaxCalled = false;
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                ajaxCalled = true;
                resolve({ vanityId: "Other" });
            };

            api.resolveVanityAssetId(vanityReference, fileUpload);
            setTimeout(function() {
                expect(ajaxCalled).toBe(false);
                expect(fileUpload.getFileReference()).toBe(vanityReference);
                done();
            }, 10);
        });

        it("does not resolve when no vanity id property is configured", function(done) {
            api.setVanityIdTestState({ vanityIdProperty: "" });
            fileUpload = imageV3EditorRemoteAssetCreateFileUploadStub(remoteFileReference);
            let ajaxCalled = false;
            globalThis.jQuery._ajaxHandler = function(options, resolve) {
                ajaxCalled = true;
                resolve({ vanityId: "VanityCheck" });
            };

            api.resolveVanityAssetId(remoteFileReference, fileUpload);
            setTimeout(function() {
                expect(ajaxCalled).toBe(false);
                expect(fileUpload.getFileReference()).toBe(remoteFileReference);
                done();
            }, 10);
        });
    });
});
