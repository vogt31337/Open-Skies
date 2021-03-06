/*
 Copyright (c) 2012 Aaron Perkins

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */
package jmeplanet;

import com.jme3.asset.AssetManager;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.bounding.BoundingSphere;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.debug.Grid;
import com.jme3.scene.shape.Sphere;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.TextureCubeMap;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Utility
 *
 */
public class Utility {

    public static final float ATMOSPHERE_MULTIPLIER = .045f;

    public static Node createGridAxis(AssetManager assetManager, int lines, int spacing) {
        Node grid = new Node("Grid Axis");

        float half_size = (lines * spacing) / 2.0f - (spacing / 2);

        Geometry xGrid = new Geometry();
        Material xMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        xMat.setColor("Color", ColorRGBA.Blue);
        xGrid.setMesh(new Grid(lines, lines, spacing));
        xGrid.setMaterial(xMat);
        grid.attachChild(xGrid);
        xGrid.setLocalTranslation(-half_size, 0, -half_size);

        Geometry yGrid = new Geometry();
        Material yMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        yMat.setColor("Color", ColorRGBA.Green);
        yGrid.setMesh(new Grid(lines, lines, spacing));
        yGrid.setMaterial(yMat);
        grid.attachChild(yGrid);
        yGrid.rotate(FastMath.HALF_PI, 0, 0);
        yGrid.setLocalTranslation(-half_size, half_size, 0);

        Geometry zGrid = new Geometry();
        Material zMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        zMat.setColor("Color", ColorRGBA.Red);
        zGrid.setMesh(new Grid(lines, lines, spacing));
        zGrid.setMaterial(zMat);
        grid.attachChild(zGrid);
        zGrid.rotate(0, 0, FastMath.HALF_PI);
        zGrid.setLocalTranslation(0, -half_size, -half_size);

        return grid;
    }

    public static class SkyBoxContainer {

        private Spatial skybox;
        private Material mat;
        private TextureCubeMap map;
        private final DesktopAssetManager am;

        public SkyBoxContainer(Spatial skybox, Material mat, TextureCubeMap map,
                DesktopAssetManager am) {
            this.skybox = skybox;
            this.mat = mat;
            this.map = map;
            this.am = am;
        }

        public Spatial getSkyBox() {
            return skybox;
        }

        public void dispose() {
            mat = null;
            skybox = null;
            map = null;
            am.clearCache();
            System.gc();
        }
    }

    public static SkyBoxContainer createSkyBox(AssetManager assetManager, String textureName, boolean correctAxis) {
        Mesh sphere = new Sphere(10, 10, 10000f);
        sphere.setStatic();
        Geometry sky = new Geometry("SkyBox", sphere);
        sky.setQueueBucket(Bucket.Sky);
        sky.setCullHint(Spatial.CullHint.Never);
        sky.setShadowMode(ShadowMode.Off);
        sky.setModelBound(new BoundingSphere(Float.POSITIVE_INFINITY, Vector3f.ZERO));

        Image cube = assetManager.loadTexture(textureName).getImage();
        if (correctAxis) {
            //get a list of the buffers storing each face
            ByteBuffer a = cube.getData(0);
            ByteBuffer b = cube.getData(1);
            ByteBuffer c = cube.getData(2);
            ByteBuffer d = cube.getData(3);
            ByteBuffer e = cube.getData(4);
            ByteBuffer f = cube.getData(5);
            //clear the list
            cube.getData().clear();
            //store x axis
            cube.getData().add(a);
            cube.getData().add(b);
            //flip y axis
            cube.getData().add(d);
            cube.getData().add(c);
            //store z axis
            cube.getData().add(e);
            cube.getData().add(f);
        }

        TextureCubeMap cubemap = new TextureCubeMap(cube);

        Material mat = new Material(assetManager, "Common/MatDefs/Misc/Sky.j3md");
        mat.setBoolean("SphereMap", false);
        mat.setTexture("Texture", cubemap);
        mat.setVector3("NormalScale", Vector3f.UNIT_XYZ);
        sky.setMaterial(mat);

        SkyBoxContainer con = new SkyBoxContainer(sky, mat, cubemap,
                (DesktopAssetManager) assetManager);
        return con;
    }

    public static Planet createEarthLikePlanet(AssetManager assetManager, float radius, Material oceanMaterial, HeightDataSource dataSource) {

        float heightScale = dataSource.getHeightScale();

        // Prepare planet material
        Material planetMaterial = new Material(assetManager, "JmePlanet/MatDefs/Terrain.j3md");

        // shore texture
        Texture dirt = assetManager.loadTexture("Textures/dirt.png");
        dirt.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region1ColorMap", dirt);
        planetMaterial.setVector3("Region1", new Vector3f(0, heightScale * 0.2f, 0));
        // grass texture
        Texture grass = assetManager.loadTexture("Textures/grass.png");
        grass.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region2ColorMap", grass);
        planetMaterial.setVector3("Region2", new Vector3f(heightScale * 0.16f, heightScale * 1.05f, 0));
        // gravel texture
        Texture gravel = assetManager.loadTexture("Textures/gravel_mud.png");
        gravel.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region3ColorMap", gravel);
        planetMaterial.setVector3("Region3", new Vector3f(heightScale * 0.84f, heightScale * 1.1f, 0));
        // snow texture
        Texture snow = assetManager.loadTexture("Textures/snow.png");
        snow.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region4ColorMap", snow);
        planetMaterial.setVector3("Region4", new Vector3f(heightScale * 0.94f, heightScale * 1.5f, 0));

        // rock texture
        Texture rock = assetManager.loadTexture("Textures/rock.png");
        rock.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("SlopeColorMap", rock);

        // create planet
        Planet planet = new Planet("Planet", radius, planetMaterial, dataSource, false);

        // create ocean
        if (oceanMaterial == null) {
            oceanMaterial = assetManager.loadMaterial("Materials/Ocean.j3m");
        }
        planet.createOcean(oceanMaterial);

        // create atmosphere
        Material atmosphereMaterial = new Material(assetManager, "JmePlanet/MatDefs/Atmosphere.j3md");
        float atmosphereRadius = radius + (radius * ATMOSPHERE_MULTIPLIER);
        atmosphereMaterial.setColor("Ambient", new ColorRGBA(0.5f, 0.5f, 1f, 1f));
        atmosphereMaterial.setColor("Diffuse", new ColorRGBA(0.18867780436772762f, 0.4978442963618773f, 0.6616065586417131f, 1.0f));
        atmosphereMaterial.setColor("Specular", new ColorRGBA(0.7f, 0.7f, 1f, 1f));
        atmosphereMaterial.setFloat("Shininess", 3.0f);

        planet.createAtmosphere(atmosphereMaterial, atmosphereRadius);

        return planet;
    }

    public static Planet createIcePlanet(AssetManager assetManager, float radius, Material oceanMaterial, HeightDataSource dataSource, int seed) {

        float heightScale = dataSource.getHeightScale();

        // Prepare planet material
        Material planetMaterial = new Material(assetManager, "JmePlanet/MatDefs/Terrain.j3md");

        // region1 texture
        Texture region1 = assetManager.loadTexture("Textures/snow.png");
        region1.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region1ColorMap", region1);
        planetMaterial.setVector3("Region1", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region2 texture
        Texture region2 = assetManager.loadTexture("Textures/moon.png");
        region2.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region2ColorMap", region2);
        planetMaterial.setVector3("Region2", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region3 texture
        Texture region3 = assetManager.loadTexture("Textures/dirty-snow.png");
        region3.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region3ColorMap", region3);
        planetMaterial.setVector3("Region3", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region4 texture
        Texture region4 = assetManager.loadTexture("Textures/ice.png");
        region4.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region4ColorMap", region4);
        planetMaterial.setVector3("Region4", new Vector3f(heightScale * 0.5f, heightScale * 1.0f, 0));

        // rock texture
        Texture rock = assetManager.loadTexture("Textures/rock.png");
        rock.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("SlopeColorMap", rock);

        // create planet
        Planet planet = new Planet("Ice", radius, planetMaterial, dataSource, false);

        // create atmosphere
        Material atmosphereMaterial = new Material(assetManager, "JmePlanet/MatDefs/Atmosphere.j3md");
        float atmosphereRadius = radius + (radius * ATMOSPHERE_MULTIPLIER);
        ColorRGBA airColor = createSeededAirColor(seed);
        atmosphereMaterial.setColor("Ambient", airColor);
        atmosphereMaterial.setColor("Diffuse", airColor);
        atmosphereMaterial.setColor("Specular", airColor);
        atmosphereMaterial.setFloat("Shininess", 3.0f);

        planet.createAtmosphere(atmosphereMaterial, atmosphereRadius);

        return planet;
    }

    public static Planet createMarsLikePlanet(AssetManager assetManager, float radius, Material oceanMaterial, HeightDataSource dataSource, boolean wetMars, int seed) {

        float heightScale = dataSource.getHeightScale();

        // Prepare planet material
        Material planetMaterial = new Material(assetManager, "JmePlanet/MatDefs/Terrain.j3md");

        // lowlands
        Texture dirt = assetManager.loadTexture("Textures/mars_lowland.png");
        dirt.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region1ColorMap", dirt);
        planetMaterial.setVector3("Region1", new Vector3f(0, heightScale * 0.2f, 0));
        // midlands
        Texture grass = assetManager.loadTexture("Textures/mars_midland.png");
        grass.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region2ColorMap", grass);
        planetMaterial.setVector3("Region2", new Vector3f(heightScale * 0.16f, heightScale * 1.05f, 0));
        // highlands
        Texture gravel = assetManager.loadTexture("Textures/mars_highland.png");
        gravel.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region3ColorMap", gravel);
        planetMaterial.setVector3("Region3", new Vector3f(heightScale * 0.84f, heightScale * 1.1f, 0));
        // snow texture
        Texture snow = assetManager.loadTexture("Textures/snow.png");
        snow.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region4ColorMap", snow);
        planetMaterial.setVector3("Region4", new Vector3f(heightScale * 0.94f, heightScale * 1.5f, 0));

        // rock texture
        Texture rock = assetManager.loadTexture("Textures/rock.png");
        rock.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("SlopeColorMap", rock);

        // create planet
        Planet planet = new Planet("Planet", radius, planetMaterial, dataSource, false);
        if (wetMars) {
            // create ocean
            if (oceanMaterial == null) {
                oceanMaterial = assetManager.loadMaterial("Materials/Ocean.j3m");
            }
            planet.createOcean(oceanMaterial);
        }
        // create atmosphere
        Material atmosphereMaterial = new Material(assetManager, "JmePlanet/MatDefs/Atmosphere.j3md");
        float atmosphereRadius = radius + (radius * ATMOSPHERE_MULTIPLIER);
        ColorRGBA airColor = createSeededAirColor(seed);
        atmosphereMaterial.setColor("Ambient", airColor);
        atmosphereMaterial.setColor("Diffuse", airColor);
        atmosphereMaterial.setColor("Specular", airColor);
        atmosphereMaterial.setFloat("Shininess", 3.0f);

        planet.createAtmosphere(atmosphereMaterial, atmosphereRadius);

        return planet;
    }

    public static Planet createMoonLikePlanet(AssetManager assetManager, float radius, HeightDataSource dataSource) {

        float heightScale = dataSource.getHeightScale();

        // Prepare planet material
        Material planetMaterial = new Material(assetManager, "JmePlanet/MatDefs/Terrain.j3md");

        // region1 texture
        Texture region1 = assetManager.loadTexture("Textures/moon_sea.png");
        region1.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region1ColorMap", region1);
        planetMaterial.setVector3("Region1", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region2 texture
        Texture region2 = assetManager.loadTexture("Textures/moon.png");
        region2.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region2ColorMap", region2);
        planetMaterial.setVector3("Region2", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region3 texture
        Texture region3 = assetManager.loadTexture("Textures/moon.png");
        region3.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region3ColorMap", region3);
        planetMaterial.setVector3("Region3", new Vector3f(heightScale * 0f, heightScale * 0.75f, 0));
        // region4 texture
        Texture region4 = assetManager.loadTexture("Textures/moon_rough.png");
        region4.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("Region4ColorMap", region4);
        planetMaterial.setVector3("Region4", new Vector3f(heightScale * 0.5f, heightScale * 1.0f, 0));

        // rock texture
        Texture rock = assetManager.loadTexture("Textures/rock.png");
        rock.setWrap(Texture.WrapMode.Repeat);
        planetMaterial.setTexture("SlopeColorMap", rock);

        // create planet
        Planet planet = new Planet("Moon", radius, planetMaterial, dataSource, false);

        return planet;
    }

    public static Planet createAtmosphereShell(AssetManager assetManager, float radius, HeightDataSource dataSource, ColorRGBA color) {
        // Prepare planet material
        Material planetMaterial = new Material(assetManager, "JmePlanet/MatDefs/Terrain.j3md");
        // create planet
        Planet planet = new Planet("Atmosphere Shell", radius, planetMaterial, dataSource, true);
        // create atmosphere
        Material atmosphereMaterial = new Material(assetManager, "JmePlanet/MatDefs/Atmosphere.j3md");
        float atmosphereRadius = radius + (radius * ATMOSPHERE_MULTIPLIER);
        atmosphereMaterial.setColor("Ambient", color.mult(0.1f));
        atmosphereMaterial.setColor("Diffuse", color);
        atmosphereMaterial.setColor("Specular", new ColorRGBA(0.01f, 0.01f, 0.01f, 0.01f));
        atmosphereMaterial.setFloat("Shininess", 3.0f);
        planet.createAtmosphere(atmosphereMaterial, atmosphereRadius);
        return planet;

    }

    public static ColorRGBA createSeededAirColor(int seed) {
        Random rnd = new Random(seed);
        float r = rnd.nextFloat() / 2;
        float g = rnd.nextFloat() / 2;
        float b = rnd.nextFloat() / 2;
        float a = rnd.nextFloat() / 4;
        return new ColorRGBA(r, g, b, a);
    }
}
