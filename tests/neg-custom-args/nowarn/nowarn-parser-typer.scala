import annotation.nowarn

// parser warnigns are buffered until the end of typer (where `@nowarn` annotations are handled).

// separate test file, because with `-Werror` this test only runs to the end of type checking.
// later warnings (eg deprecations in refchecks) are not issued.

def t1a = try 1 // error, parser warning
@nowarn("msg=try without catch") def t1b = try 1

@deprecated def f = 0

def t2 = f // not reported because refchecks doesn't run

@nowarn("wat?") // error, invalid filter
def t3 = { 1; 2 } // error, the invalid nowarn doesn't silence this warning

@nowarn("id=E129") def t4 = { 1; 2 }
@nowarn("id=E000") def t5a = try 1
@nowarn("id=E0") def t5b = try 1
@nowarn("id=0") def t5c = try 1
@nowarn("id=1") def t5d = try 1 // error, wrong id
