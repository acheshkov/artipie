/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.artipie;

import com.artipie.asto.Storage;
import com.artipie.composer.http.PhpComposer;
import com.artipie.docker.asto.AstoDocker;
import com.artipie.docker.http.DockerSlice;
import com.artipie.docker.proxy.ClientSlice;
import com.artipie.docker.proxy.ProxyDocker;
import com.artipie.files.FilesSlice;
import com.artipie.gem.GemSlice;
import com.artipie.helm.HelmSlice;
import com.artipie.http.GoSlice;
import com.artipie.http.Slice;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicIdentities;
import com.artipie.http.auth.Permissions;
import com.artipie.http.slice.TrimPathSlice;
import com.artipie.maven.http.MavenProxySlice;
import com.artipie.maven.http.MavenSlice;
import com.artipie.maven.repository.StorageCache;
import com.artipie.npm.Npm;
import com.artipie.npm.http.NpmSlice;
import com.artipie.npm.proxy.NpmProxy;
import com.artipie.npm.proxy.NpmProxyConfig;
import com.artipie.npm.proxy.http.NpmProxySlice;
import com.artipie.nuget.http.NuGet;
import com.artipie.pypi.PySlice;
import com.artipie.rpm.http.RpmSlice;
import com.jcabi.log.Logger;
import io.vertx.reactivex.core.Vertx;
import java.net.URI;
import java.util.regex.Pattern;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Slice from repo config.
 * @since 0.1.4
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle ParameterNameCheck (500 lines)
 * @checkstyle ParameterNumberCheck (500 lines)
 * @checkstyle CyclomaticComplexityCheck (500 lines)
 * @checkstyle ClassFanOutComplexityCheck (500 lines)
 */
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.StaticAccessToStaticFields"})
public final class SliceFromConfig extends Slice.Wrap {

    /**
     * Http client.
     */
    private static final HttpClient HTTP;

    static {
        final boolean trustall = "true".equals(System.getenv("SSL_TRUSTALL"));
        HTTP = new HttpClient(new SslContextFactory.Client(trustall));
        Logger.info(SliceFromConfig.class, "Created HTTP client, trustall=%b", trustall);
        try {
            SliceFromConfig.HTTP.start();
            // @checkstyle IllegalCatchCheck (1 line)
        } catch (final Exception err) {
            throw new IllegalStateException(err);
        }
        final ProxyConfiguration config = SliceFromConfig.HTTP.getProxyConfiguration();
        final String phost = System.getProperty("http.proxyHost");
        final String pport = System.getProperty("http.proxyPort");
        if (phost != null && pport != null) {
            final HttpProxy proxy = new HttpProxy(
                new Origin.Address(
                    phost,
                    Integer.parseInt(pport)
                ),
                false
            );
            config.getProxies().add(proxy);
            Logger.info(SliceFromConfig.class, "Added HTTP client proxy: %s", proxy);
        }
    }

    /**
     * Ctor.
     * @param config Repo config
     * @param vertx Vertx instance
     * @param auth Authentication
     * @param prefix Path prefix
     */
    public SliceFromConfig(final RepoConfig config, final Vertx vertx,
        final Authentication auth, final Pattern prefix) {
        super(
            SliceFromConfig.build(config, vertx, auth, prefix)
        );
    }

    /**
     * Find a slice implementation for config.
     * @param cfg Repository config
     * @param vertx Vertx instance
     * @param auth Authentication implementation
     * @param prefix Path prefix pattern
     * @return Slice completionStage
     * @todo #90:30min This method still needs more refactoring.
     *  We should test if the type exist in the constructed map. If the type does not exist,
     *  we should throw an IllegalStateException with the message "Unsupported repository type '%s'"
     * @checkstyle LineLengthCheck (100 lines)
     * @checkstyle ExecutableStatementCountCheck (100 lines)
     * @checkstyle JavaNCSSCheck (500 lines)
     */
    @SuppressWarnings("PMD.CyclomaticComplexity")
    static Slice build(final RepoConfig cfg, final Vertx vertx,
        final Authentication auth, final Pattern prefix) {
        final Slice slice;
        final Storage storage = cfg.storage();
        final Permissions permissions = cfg.permissions();
        switch (cfg.type()) {
            case "file":
                slice = new TrimPathSlice(new FilesSlice(storage, permissions, auth), prefix);
                break;
            case "npm":
                slice = new NpmSlice(
                    cfg.path(),
                    new Npm(storage),
                    storage,
                    permissions,
                    new BasicIdentities(auth)
                );
                break;
            case "gem":
                slice = new GemSlice(storage, vertx.fileSystem());
                break;
            case "helm":
                slice = new HelmSlice(storage);
                break;
            case "rpm":
                slice = new TrimPathSlice(new RpmSlice(storage, permissions, auth), prefix);
                break;
            case "php":
                slice = new PhpComposer(cfg.path(), storage);
                break;
            case "nuget":
                slice = new NuGet(cfg.url(), cfg.path(), storage, permissions, auth);
                break;
            case "maven":
                slice = new TrimPathSlice(new MavenSlice(storage, permissions, auth), prefix);
                break;
            case "maven-proxy":
                slice = new TrimPathSlice(
                    new MavenProxySlice(
                        SliceFromConfig.HTTP,
                        URI.create(
                            cfg.settings()
                                .orElseThrow(() -> new IllegalStateException("Repo settings missed"))
                                .string("remote_uri")
                        ),
                        new StorageCache(storage)
                    ),
                    prefix
                );
                break;
            case "go":
                slice = new GoSlice(storage);
                break;
            case "npm-proxy":
                slice = new NpmProxySlice(
                    cfg.path(),
                    new NpmProxy(new NpmProxyConfig(cfg.settings().orElseThrow()), vertx, storage)
                );
                break;
            case "pypi":
                slice = new PySlice(cfg.path(), storage);
                break;
            case "docker":
                slice = new DockerSlice(cfg.path(), new AstoDocker(storage));
                break;
            case "docker-proxy":
                final String host = cfg.settings()
                    .orElseThrow(() -> new IllegalStateException("Repo settings not found"))
                    .string("host");
                slice = new DockerSlice(
                    cfg.path(),
                    new ProxyDocker(new ClientSlice(SliceFromConfig.HTTP, host))
                );
                break;
            default:
                throw new IllegalStateException(
                    String.format("Unsupported repository type '%s", cfg.type())
                );
        }
        return cfg.contentLengthMax()
            .<Slice>map(limit -> new ContentLengthRestriction(slice, limit))
            .orElse(slice);
    }
}
