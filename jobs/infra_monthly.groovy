import util.Plumber

def p = new Plumber(name: 'sqre/infrastructure/infra-monthly', dsl: this)
p.pipeline().with {
  description('Periodic builds of infrastructure jobs.')
}
