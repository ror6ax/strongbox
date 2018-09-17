package org.carlspring.strongbox.controllers.layout.npm;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.MediaType;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.IOUtils;
import org.carlspring.strongbox.artifact.coordinates.NpmArtifactCoordinates;
import org.carlspring.strongbox.controllers.BaseArtifactController;
import org.carlspring.strongbox.data.criteria.Expression.ExpOperator;
import org.carlspring.strongbox.data.criteria.Paginator;
import org.carlspring.strongbox.data.criteria.Predicate;
import org.carlspring.strongbox.npm.metadata.DistTags;
import org.carlspring.strongbox.npm.metadata.PackageFeed;
import org.carlspring.strongbox.npm.metadata.PackageVersion;
import org.carlspring.strongbox.npm.metadata.Time;
import org.carlspring.strongbox.npm.metadata.Versions;
import org.carlspring.strongbox.providers.ProviderImplementationException;
import org.carlspring.strongbox.providers.io.RepositoryPath;
import org.carlspring.strongbox.providers.layout.NpmPackageDesc;
import org.carlspring.strongbox.providers.layout.NpmPackageSupplier;
import org.carlspring.strongbox.providers.repository.RepositoryProvider;
import org.carlspring.strongbox.providers.repository.RepositoryProviderRegistry;
import org.carlspring.strongbox.services.ArtifactManagementService;
import org.carlspring.strongbox.storage.repository.Repository;
import org.carlspring.strongbox.storage.validation.artifact.ArtifactCoordinatesValidationException;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This Controller used to handle npm requests.
 * 
 * @author Sergey Bespalov
 *
 */
@RestController
@RequestMapping(path = NpmArtifactController.ROOT_CONTEXT, headers = "user-agent=npm/*")
public class NpmArtifactController extends BaseArtifactController
{

    private static final String FIELD_NAME_LENGTH = "length";

    private static final String FIELD_NAME_ATTACHMENTS = "_attachments";

    private static final String FIELD_NAME_VERSION = "versions";

    private static final Logger logger = LoggerFactory.getLogger(NpmArtifactController.class);

    public final static String ROOT_CONTEXT = "/storages";

    @Inject
    private ArtifactManagementService npmArtifactManagementService;

    @Inject
    @Qualifier("npmJackasonMapper")
    private ObjectMapper npmJackasonMapper;

    @Inject
    private RepositoryProviderRegistry repositoryProviderRegistry;

    @Inject
    private NpmPackageSupplier npmPackageSupplier;
    
    
    @RequestMapping(path = { "{storageId}/{repositoryId}/npm" }, method = RequestMethod.GET)
    public ResponseEntity<String> greet()
    {
        // TODO: find out what NPM expect to receive here
        return ResponseEntity.ok("");
    }

    @RequestMapping(path = "{storageId}/{repositoryId}/{packageScope}/{packageName:[^-].+}/{packageVersion}", method = { RequestMethod.GET })
    @PreAuthorize("hasAuthority('ARTIFACTS_VIEW')")
    public void viewPackageWithScope(@PathVariable(name = "storageId") String storageId,
                                     @PathVariable(name = "repositoryId") String repositoryId,
                                     @PathVariable(name = "packageScope") String packageScope,
                                     @PathVariable(name = "packageName") String packageName,
                                     @PathVariable(name = "packageVersion") String packageVersion,
                                     @RequestHeader HttpHeaders httpHeaders,
                                     HttpServletRequest request,
                                     HttpServletResponse response)
        throws Exception
    {
        String packageId = NpmArtifactCoordinates.caclulatePackageId(packageScope, packageName);
        NpmArtifactCoordinates c = NpmArtifactCoordinates.of(packageId, packageVersion);
        
        RepositoryPath repositoryPath = artifactResolutionService.resolvePath(storageId, repositoryId, c.toPath());
        if (repositoryPath == null)
        {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            
            return;
        }
        
        NpmPackageDesc packageDesc = npmPackageSupplier.apply(repositoryPath);
        PackageVersion npmPackage = packageDesc.getNpmPackage();
        
        response.setContentType(MediaType.APPLICATION_JSON);
        response.getOutputStream().write(npmJackasonMapper.writeValueAsBytes(npmPackage));
    }
    
    @RequestMapping(path = "{storageId}/{repositoryId}/{packageScope}/{packageName}", method = { RequestMethod.GET})
    @PreAuthorize("hasAuthority('ARTIFACTS_VIEW')")
    public void viewPackageFeedWithScope(@PathVariable(name = "storageId") String storageId,
                                         @PathVariable(name = "repositoryId") String repositoryId,
                                         @PathVariable(name = "packageScope") String packageScope,
                                         @PathVariable(name = "packageName") String packageName,
                                         @RequestHeader HttpHeaders httpHeaders,
                                         HttpServletRequest request,
                                         HttpServletResponse response)
        throws Exception
    {
        String packageId = NpmArtifactCoordinates.caclulatePackageId(packageScope, packageName);
        
        Repository repository = getRepository(storageId, repositoryId);

        PackageFeed packageFeed = new PackageFeed();

        packageFeed.setName(packageId);
        packageFeed.setAdditionalProperty("_id", packageId);
        
        Predicate predicate = createSearchPredicate(packageScope, packageName);

        RepositoryProvider provider = repositoryProviderRegistry.getProvider(repository.getType());

        Paginator paginator = new Paginator();
        paginator.setProperty("version");

        List<Path> searchResult = provider.search(storageId, repositoryId, predicate, paginator);

        Versions versions = new Versions();
        packageFeed.setVersions(versions);

        Time npmTime = new Time();
        packageFeed.setTime(npmTime);

        DistTags distTags = new DistTags();
        packageFeed.setDistTags(distTags);

        searchResult.stream().map(npmPackageSupplier).forEach(p -> {
            PackageVersion npmPackage = p.getNpmPackage();
            versions.setAdditionalProperty(npmPackage.getVersion(), npmPackage);

            npmTime.setAdditionalProperty(npmPackage.getVersion(), p.getReleaseDate());

            Date created = npmTime.getCreated();
            npmTime.setCreated(created == null || created.before(p.getReleaseDate()) ? p.getReleaseDate() : created);

            Date modified = npmTime.getModified();
            npmTime.setModified(modified == null || modified.before(p.getReleaseDate()) ? p.getReleaseDate()
                    : modified);

            if (p.isLastVersion())
            {
                distTags.setLatest(npmPackage.getVersion());
            }

        });

        response.setContentType(MediaType.APPLICATION_JSON);
        response.getOutputStream().write(npmJackasonMapper.writeValueAsBytes(packageFeed));
    }

    @RequestMapping(path = "{storageId}/{repositoryId}/{packageName}", method = { RequestMethod.GET})
    @PreAuthorize("hasAuthority('ARTIFACTS_VIEW')")
    public void viewPackageFeed(@PathVariable(name = "storageId") String storageId,
                                @PathVariable(name = "repositoryId") String repositoryId,
                                @PathVariable(name = "packageName") String packageName,
                                @RequestHeader HttpHeaders httpHeaders,
                                HttpServletRequest request,
                                HttpServletResponse response)
        throws Exception
    {
        viewPackageFeedWithScope(storageId, repositoryId, null, packageName, httpHeaders, request, response);
    }

    private Predicate createSearchPredicate(String packageScope,
                                            String packageName)
    {
        Predicate rootPredicate = Predicate.empty();

        rootPredicate.and(Predicate.of(ExpOperator.EQ.of("artifactCoordinates.coordinates.extension", "tgz")));
        rootPredicate.and(Predicate.of(ExpOperator.EQ.of("artifactCoordinates.coordinates.name", packageName)));
        if (packageScope != null)
        {
            rootPredicate.and(Predicate.of(ExpOperator.EQ.of("artifactCoordinates.coordinates.scope", packageScope)));
        }

        return rootPredicate;
    }

    @PreAuthorize("hasAuthority('ARTIFACTS_RESOLVE')")
    @RequestMapping(path = "{storageId}/{repositoryId}/{packageScope}/{packageName}/-/{packageName}-{packageVersion}.{packageExtension}", method = { RequestMethod.GET,
                                                                                                                                                     RequestMethod.HEAD })
    public void downloadPackageWithScope(@PathVariable(name = "storageId") String storageId,
                                         @PathVariable(name = "repositoryId") String repositoryId,
                                         @PathVariable(name = "packageScope") String packageScope,
                                         @PathVariable(name = "packageName") String packageName,
                                         @PathVariable(name = "packageVersion") String packageVersion,
                                         @PathVariable(name = "packageExtension") String packageExtension,
                                         @RequestHeader HttpHeaders httpHeaders,
                                         HttpServletRequest request,
                                         HttpServletResponse response)
        throws Exception
    {
        NpmArtifactCoordinates coordinates;
        try
        {
            coordinates = NpmArtifactCoordinates.of(String.format("%s/%s", packageScope, packageName), packageVersion);
        }
        catch (IllegalArgumentException e)
        {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.getWriter().write(e.getMessage());
            return;
        }

        RepositoryPath path = artifactResolutionService.resolvePath(storageId, repositoryId, coordinates.toPath());
        provideArtifactDownloadResponse(request, response, httpHeaders, path);
    }

    @PreAuthorize("hasAuthority('ARTIFACTS_RESOLVE')")
    @RequestMapping(path = "{storageId}/{repositoryId}/{packageName}/-/{packageName}-{packageVersion}.{packageExtension}", method = { RequestMethod.GET,
                                                                                                                                      RequestMethod.HEAD })
    public void downloadPackage(@PathVariable(name = "storageId") String storageId,
                                @PathVariable(name = "repositoryId") String repositoryId,
                                @PathVariable(name = "packageName") String packageName,
                                @PathVariable(name = "packageVersion") String packageVersion,
                                @PathVariable(name = "packageExtension") String packageExtension,
                                @RequestHeader HttpHeaders httpHeaders,
                                HttpServletRequest request,
                                HttpServletResponse response)
        throws Exception
    {
        NpmArtifactCoordinates coordinates;
        try
        {
            coordinates = NpmArtifactCoordinates.of(packageName, packageVersion);
        }
        catch (IllegalArgumentException e)
        {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.getWriter().write(e.getMessage());
            return;
        }

        RepositoryPath path = artifactResolutionService.resolvePath(storageId, repositoryId, coordinates.toPath());
        provideArtifactDownloadResponse(request, response, httpHeaders, path);
    }

    @PreAuthorize("hasAuthority('ARTIFACTS_DEPLOY')")
    @RequestMapping(path = "{storageId}/{repositoryId}/{name:.+}", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON)
    public ResponseEntity publish(@PathVariable(name = "storageId") String storageId,
                                  @PathVariable(name = "repositoryId") String repositoryId,
                                  @PathVariable(name = "name") String name,
                                  HttpServletRequest request)
        throws Exception
    {
        logger.info(String.format("npm publish request for [%s]/[%s]/[%s]", storageId,
                                  repositoryId, name));
        Pair<PackageVersion, Path> packageEntry;
        try
        {
            packageEntry = extractPackage(storageId, repositoryId, name,
                                          request.getInputStream());
        }
        catch (IllegalArgumentException e)
        {
            logger.error("Failed to extract npm package data", e);
            return ResponseEntity.badRequest().build();
        }

        PackageVersion packageJson = packageEntry.getValue0();
        Path packageTgz = packageEntry.getValue1();

        Repository repository = getRepository(storageId, repositoryId);
        NpmArtifactCoordinates coordinates = NpmArtifactCoordinates.of(name, packageJson.getVersion());

        storeNpmPackage(repository, coordinates, packageJson, packageTgz);

        return ResponseEntity.ok("");
    }

    private void storeNpmPackage(Repository repository,
                                 NpmArtifactCoordinates coordinates,
                                 PackageVersion packageDef,
                                 Path packageTgzTmp)
        throws IOException,
               ProviderImplementationException,
               NoSuchAlgorithmException,
               ArtifactCoordinatesValidationException
    {
        RepositoryPath repositoryPath = repositoryPathResolver.resolve(repository, coordinates);
        try (InputStream is = new BufferedInputStream(Files.newInputStream(packageTgzTmp)))
        {
            npmArtifactManagementService.validateAndStore(repositoryPath, is);
        }

        Path packageJsonTmp = extractPackageJson(packageTgzTmp);
        RepositoryPath packageJsonPath = repositoryPathResolver.resolve(repository, repositoryPath.resolveSibling("package.json"));
        try (InputStream is = new BufferedInputStream(Files.newInputStream(packageJsonTmp)))
        {
            npmArtifactManagementService.validateAndStore(packageJsonPath, is);
        }

        String shasum = Optional.ofNullable(packageDef.getDist()).map(p -> p.getShasum()).orElse(null);
        if (shasum == null)
        {
            logger.warn(String.format("No checksum provided for package [%s]", packageDef.getName()));
            return;
        }

        String packageFileName = repositoryPath.getFileName().toString();
        RepositoryPath checksumPath = repositoryPath.resolveSibling(packageFileName + ".sha1");
        npmArtifactManagementService.validateAndStore(checksumPath,
                                                      new ByteArrayInputStream(shasum.getBytes("UTF-8")));

        Files.delete(packageTgzTmp);
        Files.delete(packageJsonTmp);
    }

    private Pair<PackageVersion, Path> extractPackage(String storageId,
                                                      String repositoryId,
                                                      String packageName,
                                                      ServletInputStream in)
        throws IOException
    {
        Path packageSourceTmp = Files.createTempFile("package", "source");
        Files.copy(in, packageSourceTmp, StandardCopyOption.REPLACE_EXISTING);

        PackageVersion packageVersion = null;
        Path packageTgzPath = null;

        JsonFactory jfactory = new JsonFactory();
        try (InputStream tmpIn = new BufferedInputStream(Files.newInputStream(packageSourceTmp)))
        {
            JsonParser jp = jfactory.createParser(tmpIn);
            jp.setCodec(npmJackasonMapper);

            Assert.isTrue(jp.nextToken() == JsonToken.START_OBJECT, "npm package source should be JSON object.");

            while (jp.nextToken() != null)
            {
                String fieldname = jp.getCurrentName();
                // read value
                if (fieldname == null)
                {
                    continue;
                }
                switch (fieldname)
                {
                case FIELD_NAME_VERSION:
                    jp.nextValue();
                    JsonNode node = jp.readValueAsTree();
                    Assert.isTrue(node.size() == 1, "npm package source should contain only one version.");

                    JsonNode packageJsonNode = node.iterator().next();
                    packageVersion = extractPackageVersion(packageName, packageJsonNode.toString());

                    break;
                case FIELD_NAME_ATTACHMENTS:
                    Assert.isTrue(jp.nextToken() == JsonToken.START_OBJECT,
                                  String.format("Failed to parse npm package source for illegal type [%s] of attachment.",
                                                jp.currentToken().name()));

                    String packageAttachmentName = jp.nextFieldName();
                    logger.info(String.format("Found npm package attachment [%s]", packageAttachmentName));

                    moveToAttachment(jp, packageAttachmentName);
                    packageTgzPath = extractPackage(jp);

                    jp.nextToken();
                    jp.nextToken();

                    break;
                }
            }
        }

        Files.delete(packageSourceTmp);

        if (packageVersion == null || packageTgzPath == null)
        {
            throw new IllegalArgumentException(
                    String.format("Failed to parse npm package source for [%s], attachment not found", packageName));
        }

        return Pair.with(packageVersion, packageTgzPath);
    }

    private Path extractPackage(JsonParser jp)
        throws IOException
    {
        Path packageTgzTmp = Files.createTempFile("package", "tgz");
        try (OutputStream packageTgzOut = new BufferedOutputStream(Files.newOutputStream(packageTgzTmp,
                                                                                         StandardOpenOption.TRUNCATE_EXISTING)))
        {
            jp.readBinaryValue(packageTgzOut);
        }

        long packageSize = Files.size(packageTgzTmp);

        Assert.isTrue(FIELD_NAME_LENGTH.equals(jp.nextFieldName()), "Failed to validate package content length.");
        jp.nextToken();

        Assert.isTrue(packageSize == jp.getLongValue(), "Invalid package content length.");
        jp.nextToken();

        return packageTgzTmp;
    }

    private Path extractPackageJson(Path packageTgzTmp)
        throws IOException
    {
        String packageJsonSource;
        try (InputStream packageTgzIn = new BufferedInputStream(Files.newInputStream(packageTgzTmp)))
        {
            packageJsonSource = extrectPackageJson(packageTgzIn);
        }
        Path packageJsonTmp = Files.createTempFile("package", "json");
        Files.write(packageJsonTmp, packageJsonSource.getBytes("UTF-8"), StandardOpenOption.TRUNCATE_EXISTING);

        return packageJsonTmp;
    }

    private void moveToAttachment(JsonParser jp,
                                  String packageAttachmentName)
        throws IOException
    {
        Assert.isTrue(jp.nextToken() == JsonToken.START_OBJECT,
                      String.format("Failed to parse npm package source for [%s], illegal attachment content type [%s].",
                                    packageAttachmentName, jp.currentToken().name()));

        jp.nextToken();
        String contentType = jp.nextTextValue();
        Assert.isTrue("application/octet-stream".equals(contentType),
                      String.format("Failed to parse npm package source for [%s], unknown content type [%s]",
                                    packageAttachmentName, contentType));

        String dataFieldName = jp.nextFieldName();
        Assert.isTrue("data".equals(dataFieldName),
                      String.format("Failed to parse npm package source for [%s], data not found",
                                    packageAttachmentName));

        jp.nextToken();
    }

    private PackageVersion extractPackageVersion(String packageName,
                                                 String packageJsonSource)
        throws IOException
    {
        PackageVersion packageVersion;
        try
        {
            packageVersion = npmJackasonMapper.readValue(packageJsonSource, PackageVersion.class);
        }
        catch (JsonProcessingException e)
        {
            throw new IllegalArgumentException(String.format("Failed to parse package.json info for [%s]", packageName),
                    e);
        }
        Assert.isTrue(packageName.equals(packageVersion.getName()),
                      String.format("Package name [%s] don't match with [%s].", packageVersion.getName(), packageName));

        return packageVersion;
    }

    private String extrectPackageJson(InputStream in)
        throws IOException
    {
        GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(in);
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn))
        {
            TarArchiveEntry entry;

            while ((entry = (TarArchiveEntry) tarIn.getNextEntry()) != null)
            {
                if (!entry.getName().endsWith("package.json"))
                {
                    continue;
                }
                StringWriter writer = new StringWriter();
                IOUtils.copy(tarIn, writer, "UTF-8");
                return writer.toString();
            }
            
            return null;
        }
    }
    
}
