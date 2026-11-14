"""Derive kotlinx.serialization JVM lookup metadata from class files, without loading them."""

from dataclasses import dataclass
import io
import json
from pathlib import Path
import re
import zipfile

SERIALIZER = 'Lkotlinx/serialization/KSerializer;'
SERIALIZABLE = 'Lkotlinx/serialization/Serializable;'
POLYMORPHIC = 'Lkotlinx/serialization/Polymorphic;'
NAMED_COMPANION = 'Lkotlinx/serialization/internal/NamedCompanion;'
MANIFEST = 'metrolist-serialization-types.tsv'


class Reader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def take(self, count):
        value = self.stream.read(count)
        if len(value) != count:
            raise ValueError('Truncated class file')
        return value

    def number(self, size):
        return int.from_bytes(self.take(size), 'big')


@dataclass
class ClassFile:
    name: str
    fields: list
    methods: list
    annotations: dict


def read_class(data):
    reader = Reader(data)
    if reader.take(4) != b'\xca\xfe\xba\xbe':
        raise ValueError('Invalid class file magic')
    reader.take(4)
    pool = [None] * reader.number(2)
    index = 1
    while index < len(pool):
        tag = reader.number(1)
        if tag == 1:
            pool[index] = reader.take(reader.number(2)).decode('utf-8', errors='replace')
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = reader.number(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            reader.take(4)
        elif tag in (5, 6):
            reader.take(8)
            index += 1
        elif tag == 15:
            reader.take(3)
        else:
            raise ValueError(f'Unknown class constant tag {tag}')
        index += 1
    reader.take(2)
    name = pool[pool[reader.number(2)]]
    reader.take(2)
    reader.take(2 * reader.number(2))

    def attributes():
        return [(pool[reader.number(2)], reader.take(reader.number(4)))
                for _ in range(reader.number(2))]

    def members():
        result = []
        for _ in range(reader.number(2)):
            access, member, descriptor = (reader.number(2) for _ in range(3))
            attributes()
            result.append((pool[member], pool[descriptor], access))
        return result

    fields, methods = members(), members()
    annotations = {}
    for attribute, raw in attributes():
        if attribute != 'RuntimeVisibleAnnotations':
            continue
        values = Reader(raw)

        def annotation():
            descriptor = pool[values.number(2)]
            arguments = {}
            for _ in range(values.number(2)):
                key = pool[values.number(2)]
                arguments[key] = element()
            return descriptor, arguments

        def element():
            tag = values.take(1)
            if tag == b'@':
                return annotation()
            elif tag == b'[':
                return [element() for _ in range(values.number(2))]
            elif tag == b'e':
                return (pool[values.number(2)], pool[values.number(2)])
            elif tag in (b's', b'c'):
                return pool[values.number(2)]
            elif tag in b'BCDFIJSZ':
                return values.number(2)
            else:
                raise ValueError('Invalid annotation element')

        for _ in range(values.number(2)):
            descriptor, arguments = annotation()
            annotations[descriptor] = arguments
    return ClassFile(name, fields, methods, annotations)


def scan_classpath(classpath):
    classes = {}
    for path in classpath:
        path = Path(path)
        if path.is_dir():
            entries = ((file.relative_to(path).as_posix(), file.read_bytes())
                       for file in sorted((path / 'com/metrolist').rglob('*.class')))
            for name, data in entries:
                if name[:-6] not in classes:
                    classes[name[:-6]] = read_class(data)
        else:
            with zipfile.ZipFile(path) as archive:
                for name in sorted(archive.namelist()):
                    if name.startswith('com/metrolist/') and name.endswith('.class'):
                        # The first classpath definition wins, as it does on the JVM.
                        if name[:-6] not in classes:
                            classes[name[:-6]] = read_class(archive.read(name))
    return classes


def serializer_methods(clazz):
    return [(name, descriptor, access) for name, descriptor, access in clazz.methods
            if name == 'serializer' and re.fullmatch(r'\((' + SERIALIZER + r')*\)' + SERIALIZER, descriptor)]


def generate(classpath, output):
    classes = scan_classpath(classpath)
    entries, types = {}, {}

    def entry(name):
        return entries.setdefault(name, {'type': name.replace('/', '.')})

    def field(name, member):
        fields = entry(name).setdefault('fields', [])
        if {'name': member} not in fields:
            fields.append({'name': member})

    def methods(clazz):
        found = serializer_methods(clazz)
        if found:
            entry(clazz.name)['methods'] = [
                {'name': 'serializer', 'parameterTypes': ['kotlinx.serialization.KSerializer'] * descriptor.split(')')[0].count(SERIALIZER)}
                for _, descriptor, _ in found]
        return max((descriptor.split(')')[0].count(SERIALIZER) for _, descriptor, _ in found), default=0)

    # Platform.kt in kotlinx-serialization-core-jvm 1.12.0 checks the default
    # companion, objects, named companions / $$serializer, then polymorphic annotations.
    for name, clazz in sorted(classes.items()):
        companions = [(member, classes[descriptor[1:-1]])
                      for member, descriptor, access in clazz.fields
                      if access & 8 and descriptor.startswith('L') and descriptor[1:-1] in classes
                      and (member == 'Companion' or NAMED_COMPANION in classes[descriptor[1:-1]].annotations)
                      and serializer_methods(classes[descriptor[1:-1]])]
        is_object = any(member == 'INSTANCE' and descriptor == f'L{name};' and access & 8
                        for member, descriptor, access in clazz.fields) and bool(serializer_methods(clazz))
        generated = classes.get(name + '$$serializer')
        polymorphic = POLYMORPHIC in clazz.annotations or clazz.annotations.get(SERIALIZABLE, {}).get('with') == 'Lkotlinx/serialization/PolymorphicSerializer;'
        if not (companions or is_object or generated or polymorphic):
            continue
        entry(name)
        arity = 0
        for member, companion in companions:
            field(name, member)
            arity = max(arity, methods(companion))
            if member != 'Companion':
                entry(name)['allDeclaredFields'] = True
        if is_object:
            field(name, 'INSTANCE')
            entry(name)['allDeclaredFields'] = True
            entry(name)['allPublicMethods'] = True
            methods(clazz)
        if generated and not companions and not is_object and any(member == 'INSTANCE' for member, _, _ in generated.fields):
            field(generated.name, 'INSTANCE')
        if polymorphic:
            entry(name)['allDeclaredFields'] = True
        types[name] = arity
    output.mkdir(parents=True, exist_ok=True)
    metadata = {'reflection': [entries[name] for name in sorted(entries)], 'resources': [{'glob': MANIFEST}]}
    (output / 'reachability-metadata.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf-8')
    (output / MANIFEST).write_text(''.join(f'{name.replace("/", ".")}\t{arity}\n' for name, arity in sorted(types.items())), encoding='utf-8')
    print(f'Serialization metadata: {len(entries)} reflection entries, {len(types)} types', flush=True)
    return metadata
