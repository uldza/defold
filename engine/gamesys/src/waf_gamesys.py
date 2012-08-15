import Task, TaskGen, Utils, re, os, sys
from TaskGen import extension
from waf_content import proto_compile_task
from threading import Lock

stderr_lock = Lock()

def configure(conf):
    conf.find_file('meshc.py', var='MESHC', mandatory = True)

def transform_texture_name(task, name):
    name = name.replace('.png', '.texturec')
    name = name.replace('.jpg', '.texturec')
    return name

def transform_collection(task, msg):
    for i in msg.instances:
        i.prototype = i.prototype.replace('.go', '.goc')
    for c in msg.collection_instances:
        c.collection = c.collection.replace('.collection', '.collectionc')
    return msg

def transform_collectionproxy(task, msg):
    msg.collection = msg.collection.replace('.collection', '.collectionc')
    return msg

def transform_collisionobject(task, msg):
    import physics_ddf_pb2
    import google.protobuf.text_format
    import ddf.ddf_math_pb2
    if msg.type != physics_ddf_pb2.COLLISION_OBJECT_TYPE_DYNAMIC:
        msg.mass = 0

    # Merge convex shape resource with collision object
    # NOTE: Special case for tilegrid resources. They are left as is
    if msg.collision_shape and not (msg.collision_shape.endswith('.tilegrid') or msg.collision_shape.endswith('.tilemap')):
        p = os.path.join(task.generator.content_root, msg.collision_shape[1:])
        convex_msg = physics_ddf_pb2.ConvexShape()
        with open(p, 'rb') as in_f:
            google.protobuf.text_format.Merge(in_f.read(), convex_msg)
            shape = msg.embedded_collision_shape.shapes.add()
            shape.shape_type = convex_msg.shape_type
            shape.position.x = shape.position.y = shape.position.z = 0
            shape.rotation.x = shape.rotation.y = shape.rotation.z = 0
            shape.rotation.w = 1
            shape.index = len(msg.embedded_collision_shape.data)
            shape.count = len(convex_msg.data)

            for x in convex_msg.data:
                msg.embedded_collision_shape.data.append(x)

        msg.collision_shape = ''

    msg.collision_shape = msg.collision_shape.replace('.convexshape', '.convexshapec')
    msg.collision_shape = msg.collision_shape.replace('.tilegrid', '.tilegridc')
    msg.collision_shape = msg.collision_shape.replace('.tilemap', '.tilegridc')
    return msg

def transform_emitter(task, msg):
    msg.material = msg.material.replace('.material', '.materialc')
    msg.texture.name = transform_texture_name(task, msg.texture.name)
    return msg

def transform_gameobject(task, msg):
    for c in msg.components:
        c.component = c.component.replace('.camera', '.camerac')
        c.component = c.component.replace('.collectionproxy', '.collectionproxyc')
        c.component = c.component.replace('.collisionobject', '.collisionobjectc')
        c.component = c.component.replace('.emitter', '.emitterc')
        c.component = c.component.replace('.gui', '.guic')
        c.component = c.component.replace('.model', '.modelc')
        c.component = c.component.replace('.script', '.scriptc')
        c.component = c.component.replace('.wav', '.wavc')
        c.component = c.component.replace('.factory', '.factoryc')
        c.component = c.component.replace('.light', '.lightc')
        c.component = c.component.replace('.sprite', '.spritec')
        c.component = c.component.replace('.tileset', '.tilesetc')
        c.component = c.component.replace('.tilesource', '.tilesetc')
        c.component = c.component.replace('.tilegrid', '.tilegridc')
        c.component = c.component.replace('.tilemap', '.tilegridc')
    return msg

def transform_model(task, msg):
    msg.mesh = msg.mesh.replace('.dae', '.meshc')
    msg.material = msg.material.replace('.material', '.materialc')
    for i,n in enumerate(msg.textures):
        msg.textures[i] = transform_texture_name(task, msg.textures[i])
    return msg

def transform_gui(task, msg):
    msg.script = msg.script.replace('.gui_script', '.gui_scriptc')
    font_names = set()
    texture_names = set()
    for f in msg.fonts:
        font_names.add(f.name)
        f.font = f.font.replace('.font', '.fontc')
    for t in msg.textures:
        texture_names.add(t.name)
        t.texture = transform_texture_name(task, t.texture)
    for n in msg.nodes:
        if n.texture:
            if not n.texture in texture_names:
                raise Exception('Texture "%s" not declared in gui-file' % (n.texture))
        if n.font:
            if not n.font in font_names:
                raise Exception('Font "%s" not declared in gui-file' % (n.font))
    return msg

def transform_factory(task, msg):
    msg.prototype = msg.prototype.replace('.go', '.goc')
    return msg

def transform_render(task, msg):
    msg.script = msg.script.replace('.render_script', '.render_scriptc')
    for m in msg.materials:
        m.material = m.material.replace('.material', '.materialc')
    return msg

def transform_sprite(task, msg):
    msg.tile_set = msg.tile_set.replace('.tileset', '.tilesetc')
    msg.tile_set = msg.tile_set.replace('.tilesource', '.tilesetc')
    return msg

def transform_tilegrid(task, msg):
    msg.tile_set = msg.tile_set.replace('.tileset', '.tilesetc')
    msg.tile_set = msg.tile_set.replace('.tilesource', '.tilesetc')
    return msg

def write_embedded(task):
    try:
        import google.protobuf.text_format
        import gameobject_ddf_pb2
        msg = gameobject_ddf_pb2.PrototypeDesc()
        with open(task.inputs[0].srcpath(task.env), 'rb') as in_f:
            google.protobuf.text_format.Merge(in_f.read(), msg)

        msg = transform_gameobject(task, msg)

        for i, c in enumerate(msg.embedded_components):
            with open(task.outputs[i].bldpath(task.env), 'wb') as out_f:
                out_f.write(msg.SerializeToString())

        return 0
    except (google.protobuf.text_format.ParseError, google.protobuf.message.EncodeError) as e:
        stderr_lock.acquire()
        try:
            print >>sys.stderr, '%s: %s' % (task.inputs[0].srcpath(task.env), str(e))
        finally:
            stderr_lock.release()
        return 1

task = Task.task_type_from_func('write_embedded',
                                func    = write_embedded,
                                color   = 'RED',
                                after='proto_gen_py',
                                before='cc cxx')

def compile_go(task):
    try:
        import google.protobuf.text_format
        import gameobject_ddf_pb2
        msg = gameobject_ddf_pb2.PrototypeDesc()
        with open(task.inputs[0].srcpath(task.env), 'rb') as in_f:
            google.protobuf.text_format.Merge(in_f.read(), msg)

        for i, c in enumerate(msg.embedded_components):
            with open(task.outputs[i+1].bldpath(task.env), 'wb') as out_f:
                out_f.write(c.data)

            desc = msg.components.add()
            rel_path_dir = os.path.relpath(task.inputs[0].abspath(), task.generator.content_root)
            rel_path_dir = os.path.dirname(rel_path_dir)
            if c.id == '':
                raise Exception('Message is missing required field: id')
            desc.id = c.id
            desc.position.x = c.position.x
            desc.position.y = c.position.y
            desc.position.z = c.position.z
            desc.rotation.x = c.rotation.x
            desc.rotation.y = c.rotation.y
            desc.rotation.z = c.rotation.z
            desc.rotation.w = c.rotation.w

            desc.component = '/' + rel_path_dir + '/' + task.outputs[i+1].name

        msg = transform_gameobject(task, msg)
        while len(msg.embedded_components) > 0:
            del(msg.embedded_components[0])

        with open(task.outputs[0].bldpath(task.env), 'wb') as out_f:
            out_f.write(msg.SerializeToString())

        return 0
    except (google.protobuf.text_format.ParseError, google.protobuf.message.EncodeError, Exception) as e:
        stderr_lock.acquire()
        try:
            print >>sys.stderr, '%s: %s' % (task.inputs[0].srcpath(task.env), str(e))
        finally:
            stderr_lock.release()
        return 1

task = Task.task_type_from_func('gameobject',
                                func    = compile_go,
                                color   = 'RED',
                                after='proto_gen_py',
                                before='cc cxx')

@extension('.go')
def gofile(self, node):
    try:
        import google.protobuf.text_format
        import gameobject_ddf_pb2
        msg = gameobject_ddf_pb2.PrototypeDesc()
        with open(node.abspath(self.env), 'rb') as in_f:
            google.protobuf.text_format.Merge(in_f.read(), msg)

        task = self.create_task('gameobject')
        task.set_inputs(node)

        embed_output_nodes = []
        for i, c in enumerate(msg.embedded_components):
            name = '%s_generated_%d.%s' % (node.name.split('.')[0], i, c.type)
            embed_node = node.parent.exclusive_build_node(name)
            embed_output_nodes.append(embed_node)

            sub_task = self.create_task(c.type)
            sub_task.set_inputs(embed_node)
            out = embed_node.change_ext('.' + c.type + 'c')
            sub_task.set_outputs(out)
            sub_task.set_run_after(task)
        out = node.change_ext('.goc')
        task.set_outputs([out] + embed_output_nodes)
    except (google.protobuf.text_format.ParseError, google.protobuf.message.EncodeError, Exception) as e:
        stderr_lock.acquire()
        try:
            print >>sys.stderr, '%s: %s' % (node.srcpath(self.env), str(e))
        finally:
            stderr_lock.release()
        return 1

proto_compile_task('collection', 'gameobject_ddf_pb2', 'CollectionDesc', '.collection', '.collectionc', transform_collection)
proto_compile_task('collectionproxy', 'gamesys_ddf_pb2', 'CollectionProxyDesc', '.collectionproxy', '.collectionproxyc', transform_collectionproxy)
proto_compile_task('emitter', 'particle.particle_ddf_pb2', 'particle_ddf_pb2.Emitter', '.emitter', '.emitterc', transform_emitter)
proto_compile_task('model', 'model_ddf_pb2', 'ModelDesc', '.model', '.modelc', transform_model)
proto_compile_task('convexshape',  'physics_ddf_pb2', 'ConvexShape', '.convexshape', '.convexshapec')
proto_compile_task('collisionobject',  'physics_ddf_pb2', 'CollisionObjectDesc', '.collisionobject', '.collisionobjectc', transform_collisionobject)
proto_compile_task('gui',  'gui_ddf_pb2', 'SceneDesc', '.gui', '.guic', transform_gui)
proto_compile_task('camera', 'camera_ddf_pb2', 'CameraDesc', '.camera', '.camerac')
proto_compile_task('input_binding', 'input_ddf_pb2', 'InputBinding', '.input_binding', '.input_bindingc')
proto_compile_task('gamepads', 'input_ddf_pb2', 'GamepadMaps', '.gamepads', '.gamepadsc')
proto_compile_task('factory', 'gamesys_ddf_pb2', 'FactoryDesc', '.factory', '.factoryc', transform_factory)
proto_compile_task('light', 'gamesys_ddf_pb2', 'LightDesc', '.light', '.lightc')
proto_compile_task('render', 'render.render_ddf_pb2', 'render_ddf_pb2.RenderPrototypeDesc', '.render', '.renderc', transform_render)
proto_compile_task('sprite', 'sprite_ddf_pb2', 'SpriteDesc', '.sprite', '.spritec', transform_sprite)
proto_compile_task('tilegrid', 'tile_ddf_pb2', 'TileGrid', '.tilegrid', '.tilegridc', transform_tilegrid)
proto_compile_task('tilemap', 'tile_ddf_pb2', 'TileGrid', '.tilemap', '.tilegridc', transform_tilegrid)

TaskGen.declare_chain('project', 'cat < ${SRC} > ${TGT}', ext_in='.project', ext_out='.projectc', reentrant = False)

from cStringIO import StringIO
def strip_single_lua_comments(str):
    str = str.replace("\r", "");
    sb = StringIO()
    for line in str.split('\n'):
        lineTrimmed = line.strip()
        # Strip single line comments but preserve "pure" multi-line comments
        # Note that ---[[ is a single line comment
        # You can enable a block in Lua by adding a hyphen, e.g.
        #
        # ---[[
        # The block is enabled
        # --]]
        #

        if not lineTrimmed.startswith("--") or lineTrimmed.startswith("--[[") or lineTrimmed.startswith("--]]"):
            sb.write(line)
        sb.write("\n")
    return sb.getvalue()

def scan_lua(str):
    str = strip_single_lua_comments(str)
    ptr = re.compile('--\\[\\[.*?--\\]\\]', re.MULTILINE | re.DOTALL)
    # NOTE: We don't preserve line-numbers
    # '' could be replaced with a function
    str = ptr.sub('', str)

    modules = []
    rp1 = re.compile("require\\s*?\"(.*?)\"$")
    rp2 = re.compile("require\\s*?\\(\\s*?\"(.*?)\"\\s*?\\)$")
    for line in str.split('\n'):
        line = line.strip()
        m1 = rp1.match(line)
        m2 = rp2.match(line)
        if m1:
            modules.append(m1.group(1))
        elif m2:
            modules.append(m2.group(1))
    return modules

def compile_lua(task):
    import lua_ddf_pb2
    with open(task.inputs[0].srcpath(task.env), 'rb') as in_f:
        script = in_f.read()
        modules = scan_lua(script)
        lua_module = lua_ddf_pb2.LuaModule()
        lua_module.script = script
        lua_module.type = lua_ddf_pb2.LuaModule.TYPE_TEXT
        for m in modules:
            module_file = "/%s.lua" % m.replace(".", "/")
            lua_module.modules.append(m)
            lua_module.resources.append(module_file + 'c')

        with open(task.outputs[0].bldpath(task.env), 'wb') as out_f:
            out_f.write(lua_module.SerializeToString())

    return 0

task = Task.task_type_from_func('luascript',
                                func    = compile_lua,
                                color   = 'PINK')

@extension('.script')
def script_file(self, node):
    obj_ext = '.scriptc'
    task = self.create_task('luascript')
    task.set_inputs(node)
    out = node.change_ext(obj_ext)
    task.set_outputs(out)

@extension('.lua')
def script_file(self, node):
    obj_ext = '.luac'
    task = self.create_task('luascript')
    task.set_inputs(node)
    out = node.change_ext(obj_ext)
    task.set_outputs(out)

Task.simple_task_type('render_script', 'cat < ${SRC} > ${TGT}',
                      color='PINK',
                      before='cc cxx',
                      shell=True)

@extension('.render_script')
def testresourcecont_file(self, node):
    obj_ext = '.render_scriptc'
    task = self.create_task('render_script')
    task.set_inputs(node)
    out = node.change_ext(obj_ext)
    task.set_outputs(out)

Task.simple_task_type('wav', 'cat < ${SRC} > ${TGT}',
                      color='PINK',
                      shell=True)

@extension('.wav')
def testresourcecont_file(self, node):
    obj_ext = '.wavc'
    task = self.create_task('wav')
    task.set_inputs(node)
    out = node.change_ext(obj_ext)
    task.set_outputs(out)

Task.simple_task_type('mesh', 'python ${MESHC} ${SRC} -o ${TGT}',
                      color='PINK',
                      after='proto_gen_py',
                      before='cc cxx',
                      shell=True)

@extension('.dae')
def dae_file(self, node):
    obj_ext = '.meshc'
    mesh = self.create_task('mesh')
    mesh.set_inputs(node)
    out = node.change_ext(obj_ext)
    mesh.set_outputs(out)


Task.simple_task_type('gui_script', 'cat < ${SRC} > ${TGT}',
                      color='PINK',
                      before='cc cxx',
                      shell=True)

@extension('.gui_script')
def testresourcecont_file(self, node):
    obj_ext = '.gui_scriptc'
    task = self.create_task('gui_script')
    task.set_inputs(node)
    out = node.change_ext(obj_ext)
    task.set_outputs(out)

Task.simple_task_type('tileset', '${JAVA} -classpath ${CLASSPATH} com.dynamo.tile.TileSetc ${SRC} ${TGT}',
                      color='PINK',
                      after='proto_gen_py',
                      before='cc cxx',
                      shell=False)

@extension(['.tileset', '.tilesource'])
def tileset_file(self, node):
    classpath = [self.env['DYNAMO_HOME'] + '/ext/share/java/protobuf-java-2.3.0.jar',
                 self.env['DYNAMO_HOME'] + '/share/java/ddf.jar',
                 self.env['DYNAMO_HOME'] + '/share/java/gamesys.jar',
                 self.env['DYNAMO_HOME'] + '/share/java/tile.jar',
                 self.env['DYNAMO_HOME'] + '/ext/share/java/vecmath.jar',
                 # NOTE: Only needed when running within gamesys-project.
                 # Should be fixed somehow... in configure perhaps?
                 'default/src/java',
                 'default/src/gamesys/gamesys.jar']
    tileset = self.create_task('tileset')
    tileset.env['CLASSPATH'] = os.pathsep.join(classpath)
    tileset.set_inputs(node)
    obj_ext = '.tilesetc'
    out = node.change_ext(obj_ext)
    tileset.set_outputs(out)
