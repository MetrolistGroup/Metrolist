"""Exercise bytecode detection and JVM serializer lookup registrations without a JVM."""

import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

import native_image
from serialization_metadata import MANIFEST, NAMED_COMPANION, POLYMORPHIC, SERIALIZABLE, SERIALIZER, generate, read_class


def class_file(name, fields=(), methods=(), annotations=()):
    pool = []

    def utf(value):
        raw = value.encode()
        pool.append(b'\x01' + struct.pack('>H', len(raw)) + raw)
        return len(pool)

    def clazz(value):
        index = utf(value)
        pool.append(b'\x07' + struct.pack('>H', index))
        return len(pool)

    this, parent = clazz(name), clazz('java/lang/Object')

    def members(values):
        result = struct.pack('>H', len(values))
        for member, descriptor, access in values:
            result += struct.pack('>HHHH', access, utf(member), utf(descriptor), 0)
        return result

    body = struct.pack('>HHHH', 1, this, parent, 0) + members(fields) + members(methods)
    if annotations:
        attribute = utf('RuntimeVisibleAnnotations')
        data = struct.pack('>H', len(annotations))
        for annotation in annotations:
            if isinstance(annotation, tuple):
                descriptor, serializer = annotation
                data += struct.pack('>HHHcH', utf(descriptor), 1, utf('with'), b'c', utf(serializer))
            else:
                data += struct.pack('>HH', utf(annotation), 0)
        body += struct.pack('>HHI', 1, attribute, len(data)) + data
    else:
        body += b'\0\0'
    return b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 52, len(pool) + 1) + b''.join(pool) + body


class SerializationMetadataTest(unittest.TestCase):
    def test_companions_generics_sealed_enums_objects_and_old_serializers(self):
        classes = {}
        for kind in ('Request', 'Sealed', 'Enum', 'Generic', 'Named'):
            name = 'com/metrolist/models/' + kind
            companion = name + ('$Factory' if kind == 'Named' else '$Companion')
            arity = 2 if kind == 'Generic' else 0
            classes[name] = class_file(name, [('Factory' if kind == 'Named' else 'Companion', f'L{companion};', 25)])
            methods = [('serializer', '(' + SERIALIZER * arity + ')' + SERIALIZER, 17)]
            if arity:
                methods.append(('serializer', '()' + SERIALIZER, 17))
            # Ignore unrelated overloads with the same name and wrong return/argument types.
            methods += [('serializer', '()Ljava/lang/String;', 1), ('serializer', '(I)' + SERIALIZER, 1)]
            classes[companion] = class_file(companion, methods=methods, annotations=[NAMED_COMPANION] if kind == 'Named' else [])
        obj = 'com/metrolist/models/Object'
        classes[obj] = class_file(obj, [('INSTANCE', f'L{obj};', 25)], [('serializer', '()' + SERIALIZER, 17)])
        old = 'com/metrolist/models/Old'
        classes[old] = class_file(old)
        classes[old + '$$serializer'] = class_file(old + '$$serializer', [('INSTANCE', f'L{old}$$serializer;', 25)])
        poly = 'com/metrolist/models/Polymorphic'
        classes[poly] = class_file(poly, annotations=[POLYMORPHIC])
        classes['com/metrolist/models/Unrelated'] = class_file('com/metrolist/models/Unrelated')
        classes['other/Outside$$serializer'] = b'not a class file'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'models.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for name, data in classes.items():
                    archive.writestr(name + '.class', data)
            output = root / 'config'
            result = generate([jar], output)
            entries = {entry['type']: entry for entry in result['reflection']}
            self.assertEqual(len(entries), 14)
            self.assertEqual(entries['com.metrolist.models.Request']['fields'], [{'name': 'Companion'}])
            self.assertEqual(entries['com.metrolist.models.Generic$Companion']['methods'], [
                {'name': 'serializer', 'parameterTypes': ['kotlinx.serialization.KSerializer'] * 2},
                {'name': 'serializer', 'parameterTypes': []},
            ])
            self.assertTrue(entries['com.metrolist.models.Object']['allDeclaredFields'])
            self.assertTrue(entries['com.metrolist.models.Object']['allPublicMethods'])
            self.assertEqual(entries['com.metrolist.models.Object']['fields'], [{'name': 'INSTANCE'}])
            self.assertEqual(entries['com.metrolist.models.Old$$serializer']['fields'], [{'name': 'INSTANCE'}])
            self.assertTrue(entries['com.metrolist.models.Named']['allDeclaredFields'])
            manifest = (output / MANIFEST).read_text()
            self.assertIn('com.metrolist.models.Generic\t2\n', manifest)
            self.assertEqual(len(manifest.splitlines()), 8)
            self.assertNotIn(directory, json.dumps(result))
            self.assertEqual(json.loads((output / 'reachability-metadata.json').read_text()), result)

    def test_class_directories_precedence_and_reproducible_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = 'com/metrolist/Request'
            source = root / 'classes' / (name + '.class')
            source.parent.mkdir(parents=True)
            source.write_bytes(class_file(name, annotations=[POLYMORPHIC]))
            jar = root / 'duplicate.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr(name + '.class', class_file(name))
            first = generate([root / 'classes', jar], root / 'config')
            second = generate([root / 'classes', jar], root / 'config')
            self.assertEqual(first, second)
            self.assertEqual(first['reflection'][0]['type'], 'com.metrolist.Request')
            (root / 'classpath.txt').write_text(str(jar))
            args = native_image.arguments(root, root, root / 'image', 'linux-x64')
            config = next(arg for arg in args if arg.startswith('-H:ConfigurationFileDirectories='))
            self.assertIn(str(root / 'serialization-config'), config)
            self.assertTrue(args[args.index('-cp') + 1].endswith(str(root / 'serialization-config') + os.pathsep + str(jar)))
            self.assertEqual(generate([jar], root / 'config')['reflection'], [])
            self.assertEqual((root / 'config' / MANIFEST).read_text(), '')

    def test_polymorphic_annotation_and_custom_serializers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            jar = root / 'annotations.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                for kind, serializer in [('Poly', 'PolymorphicSerializer'), ('Custom', 'CustomSerializer')]:
                    name = 'com/metrolist/' + kind
                    archive.writestr(name + '.class', class_file(name, annotations=[
                        (SERIALIZABLE, 'Lkotlinx/serialization/' + serializer + ';'),
                    ]))
            result = generate([jar], root / 'config')
            self.assertEqual([entry['type'] for entry in result['reflection']], ['com.metrolist.Poly'])

    def test_malformed_classes_fail_instead_of_silently_omitting_models(self):
        for data in (b'nope', b'\xca\xfe\xba\xbe', class_file('com/metrolist/Test')[:-1]):
            with self.assertRaises(ValueError):
                read_class(data)


if __name__ == '__main__':
    unittest.main()
